package io.release.monorepo.steps

import cats.effect.IO
import _root_.io.release.VcsOps
import io.release.monorepo.*
import io.release.monorepo.MonorepoReleaseIO.*
import io.release.monorepo.steps.MonorepoStepHelpers.*
import io.release.steps.StepHelpers.{required, runProcess}
import sbt.Keys.*
import sbt.Project.extract
import sbtrelease.ReleasePlugin.autoImport.*
import sbtrelease.Vcs

import scala.sys.process.Process

/** VCS-related monorepo release steps. */
private[monorepo] object MonorepoVcsSteps {

  private final case class GitPushTarget(
      remote: String,
      localBranch: String,
      upstreamBranch: String
  )

  private def resolveGitPushTarget(vcs: Vcs): IO[GitPushTarget] =
    for {
      resolved <- IO.blocking {
                    val localBranch = vcs.currentBranch
                    val remote      = vcs.trackingRemote
                    val upstreamRef =
                      Process(
                        Seq(
                          "git",
                          "rev-parse",
                          "--abbrev-ref",
                          "--symbolic-full-name",
                          "@{upstream}"
                        ),
                        vcs.baseDir
                      ).!!.trim

                    val remotePrefix = s"$remote/"
                    if (!upstreamRef.startsWith(remotePrefix))
                      Left(
                        s"Upstream '$upstreamRef' for branch '$localBranch' does not match tracking remote '$remote'."
                      )
                    else {
                      val upstreamBranch = upstreamRef.stripPrefix(remotePrefix)
                      if (upstreamBranch.isEmpty)
                        Left(
                          s"Unable to resolve upstream branch from '$upstreamRef' for tracking remote '$remote'."
                        )
                      else
                        Right(
                          GitPushTarget(
                            remote = remote,
                            localBranch = localBranch,
                            upstreamBranch = upstreamBranch
                          )
                        )
                    }
                  }
      target   <- IO.fromEither(resolved.left.map(new RuntimeException(_)))
    } yield target

  val initializeVcs: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "initialize-vcs",
    action = ctx => VcsOps.detectAndInit(ctx)
  )

  val checkCleanWorkingDir: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "check-clean-working-dir",
    action = ctx => IO.pure(ctx),
    check = ctx =>
      VcsOps.checkCleanWorkingDir(ctx.state).flatMap { result =>
        logInfo(ctx, s"Starting release off commit: ${result.currentHash}")
      }
  )

  private def createTag(
      vcs: Vcs,
      tagName: String,
      comment: String,
      sign: Boolean,
      label: String
  ): IO[Unit] =
    IO.blocking(vcs.existsTag(tagName)).flatMap {
      case true  =>
        IO.raiseError(new RuntimeException(s"Tag [$tagName] already exists for $label. Aborting."))
      case false =>
        runProcess(vcs.tag(tagName, comment, sign = sign), s"vcs tag '$tagName'")
    }

  private[monorepo] val tagReleasesPerProject: MonorepoStepIO.PerProject =
    MonorepoStepIO.PerProject(
      name = "tag-release",
      action = (ctx, project) =>
        required(ctx.vcs, "VCS not initialized") { vcs =>
          required(project.versions, s"Versions not set for ${project.name}") {
            case (releaseVer, _) =>
              IO.blocking {
                val extracted = extract(ctx.state)
                (
                  extracted.get(releaseIOMonorepoTagName)(project.name, releaseVer),
                  extracted.get(releaseVcsSign)
                )
              }.flatMap { case (tagName, sign) =>
                createTag(
                  vcs,
                  tagName,
                  s"Release ${project.name} $releaseVer",
                  sign,
                  project.name
                ) *>
                  logInfo(ctx, s"Tagged ${project.name} as $tagName")
                    .as(ctx.updateProject(project.ref)(_.copy(tagName = Some(tagName))))
              }
          }
        }
    )

  private[monorepo] val tagReleasesUnified: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "tag-release",
    action = ctx =>
      required(ctx.vcs, "VCS not initialized") { vcs =>
        validateVersionConsistency(
          ctx.currentProjects,
          { case (releaseVer, _) => releaseVer },
          "Unified tag requires all projects to share the same release version. " +
            "Use per-project tag strategy or align versions"
        ) *> {
          ctx.currentProjects.flatMap(_.versions).headOption match {
            case None           =>
              IO.raiseError(new RuntimeException("No release versions set for any project"))
            case Some((rel, _)) =>
              IO.blocking {
                val extracted = extract(ctx.state)
                (
                  extracted.get(releaseIOMonorepoUnifiedTagName)(rel),
                  extracted.get(releaseVcsSign)
                )
              }.flatMap { case (tagName, sign) =>
                val summary =
                  versionSummary(ctx, { case (releaseVer, _) => releaseVer })
                createTag(vcs, tagName, s"Release: $summary", sign, "release") *>
                  logInfo(ctx, s"Tagged release as $tagName").as(
                    ctx.currentProjects.foldLeft(ctx) { (c, p) =>
                      c.updateProject(p.ref)(_.copy(tagName = Some(tagName)))
                    }
                  )
              }
          }
        }
      }
  )

  val pushChanges: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "push-changes",
    check = ctx =>
      VcsOps.detectVcs(ctx.state).flatMap { vcs =>
        for {
          hasUpAndBranch <- IO.blocking((vcs.hasUpstream, vcs.currentBranch))
          (hasUp, branch) = hasUpAndBranch
          _              <-
            IO.raiseUnless(hasUp)(
              new RuntimeException(
                s"No tracking branch configured for '$branch'. " +
                  "Set up a remote tracking branch or remove pushChanges from the release process."
              )
            )
          remote         <- IO.blocking(vcs.trackingRemote)
          remoteCode     <- IO.blocking(vcs.checkRemote(remote).!)
          _              <- IO.raiseUnless(remoteCode == 0)(
                              new RuntimeException("Remote check failed. Aborting release.")
                            )
          behind         <- IO.blocking(vcs.isBehindRemote)
          _              <-
            IO.raiseWhen(behind)(
              new RuntimeException(
                "Upstream has unmerged commits. Merge first or remove pushChanges from process."
              )
            )
        } yield ctx
      },
    action = ctx =>
      required(ctx.vcs, "VCS not initialized") { vcs =>
        for {
          hasUp  <- IO.blocking(vcs.hasUpstream)
          result <-
            if (!hasUp)
              logWarn(ctx, "No upstream branch, changes were NOT pushed.")
            else
              vcs.commandName match {
                case "git" =>
                  val tags = ctx.currentProjects.flatMap(_.tagName).distinct
                  for {
                    pushTarget <- resolveGitPushTarget(vcs)
                    _          <- logInfo(
                                    ctx,
                                    s"Pushing branch ${pushTarget.localBranch} " +
                                      s"to ${pushTarget.remote}/${pushTarget.upstreamBranch}"
                                  )
                    _          <- runProcess(
                                    Process(
                                      Seq(
                                        "git",
                                        "push",
                                        pushTarget.remote,
                                        s"${pushTarget.localBranch}:${pushTarget.upstreamBranch}"
                                      ),
                                      vcs.baseDir
                                    ),
                                    s"git push ${pushTarget.remote} ${pushTarget.localBranch}:${pushTarget.upstreamBranch}"
                                  )
                    _          <- tags.foldLeft(IO.unit) { (acc, tag) =>
                                    acc *>
                                      logInfo(ctx, s"Pushing tag $tag").void *>
                                      runProcess(
                                        Process(
                                          Seq("git", "push", pushTarget.remote, tag),
                                          vcs.baseDir
                                        ),
                                        s"git push tag '$tag'"
                                      )
                                  }
                  } yield ctx

                case _ =>
                  runProcess(vcs.pushChanges, "vcs push").as(ctx)
              }
        } yield result
      }
  )

}
