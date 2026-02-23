package io.release.monorepo.steps

import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.MonorepoReleaseIO.*
import io.release.steps.StepHelpers.runProcess
import MonorepoStepHelpers.*
import sbt.*
import sbt.Keys.*
import sbt.Project.extract
import sbtrelease.ReleasePlugin.autoImport.*
import sbtrelease.Vcs

/** VCS-related monorepo release steps. */
private[monorepo] object MonorepoVcsSteps {

  val initializeVcs: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "initialize-vcs",
    action = ctx => {
      val baseDir = extract(ctx.state).get(thisProject).base
      IO.blocking(Vcs.detect(baseDir)).flatMap {
        case Some(vcs) =>
          IO.blocking {
            val newState = extract(ctx.state).appendWithSession(
              Seq(releaseVcs := Some(vcs)),
              ctx.state
            )
            ctx.withState(newState).withVcs(vcs)
          }
        case None      =>
          IO.raiseError(new RuntimeException(s"No VCS detected at ${baseDir.getAbsolutePath}"))
      }
    }
  )

  val checkCleanWorkingDir: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "check-clean-working-dir",
    action = ctx => IO.pure(ctx),
    check = ctx => {
      val extracted       = extract(ctx.state)
      val base            = extracted.get(thisProject).base
      val ignoreUntracked = extracted.get(releaseIgnoreUntrackedFiles)

      IO.blocking(Vcs.detect(base)).flatMap {
        case None      =>
          IO.raiseError(new RuntimeException(s"No VCS detected at ${base.getAbsolutePath}"))
        case Some(vcs) =>
          IO.blocking((vcs.modifiedFiles, vcs.untrackedFiles, vcs.currentHash)).flatMap {
            case (modified, _, _) if modified.nonEmpty                       =>
              IO.raiseError(
                new RuntimeException(
                  s"Aborting release: unstaged modified files\n${modified.map(" - " + _).mkString("\n")}"
                )
              )
            case (_, untracked, _) if untracked.nonEmpty && !ignoreUntracked =>
              IO.raiseError(
                new RuntimeException(
                  s"Aborting release: untracked files\n${untracked.map(" - " + _).mkString("\n")}"
                )
              )
            case (_, _, currentHash)                                         =>
              logInfo(ctx, s"Starting release off commit: $currentHash")
          }
      }
    }
  )

  /** Create per-project tags or a unified tag depending on strategy. */
  def tagReleases(tagStrategy: MonorepoTagStrategy): MonorepoStepIO = tagStrategy match {
    case MonorepoTagStrategy.PerProject => tagReleasesPerProject
    case MonorepoTagStrategy.Unified    => tagReleasesUnified
  }

  private val tagReleasesPerProject: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "tag-release",
    action = (ctx, project) =>
      required(ctx.vcs, "VCS not initialized") { vcs =>
        required(project.versions, s"Versions not set for ${project.name}") {
          case (releaseVer, _) =>
            val extracted = extract(ctx.state)
            val tagNameFn = extracted.get(releaseIOMonorepoTagName)
            val sign      = extracted.get(releaseVcsSign)
            val tagName   = tagNameFn(project.name, releaseVer)
            val comment   = s"Release ${project.name} $releaseVer"

            IO.blocking(vcs.existsTag(tagName)).flatMap {
              case true  =>
                IO.raiseError(
                  new RuntimeException(
                    s"Tag [$tagName] already exists for ${project.name}. Aborting."
                  )
                )
              case false =>
                runProcess(vcs.tag(tagName, comment, sign = sign), s"vcs tag '$tagName'") *>
                  IO {
                    ctx.state.log.info(
                      s"[release-io-monorepo] Tagged ${project.name} as $tagName"
                    )
                    ctx.updateProject(project.ref)(_.copy(tagName = Some(tagName)))
                  }
            }
        }
      }
  )

  private val tagReleasesUnified: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "tag-release",
    action = ctx =>
      required(ctx.vcs, "VCS not initialized") { vcs =>
        validateVersionConsistency(
          ctx.currentProjects,
          _._1,
          "Unified tag requires all projects to share the same release version. " +
            "Use per-project tag strategy or align versions"
        ) *> {
          ctx.currentProjects.flatMap(_.versions).headOption match {
            case None           =>
              IO.raiseError(
                new RuntimeException("No release versions set for any project")
              )
            case Some((rel, _)) =>
              val extracted = extract(ctx.state)
              val tagNameFn = extracted.get(releaseIOMonorepoUnifiedTagName)
              val sign      = extracted.get(releaseVcsSign)
              val tagName   = tagNameFn(rel)
              val summary   = versionSummary(ctx, _._1)
              val comment   = s"Release: $summary"

              IO.blocking(vcs.existsTag(tagName)).flatMap {
                case true  =>
                  IO.raiseError(
                    new RuntimeException(s"Tag [$tagName] already exists. Aborting.")
                  )
                case false =>
                  runProcess(vcs.tag(tagName, comment, sign = sign), s"vcs tag '$tagName'") *>
                    IO {
                      ctx.state.log.info(s"[release-io-monorepo] Tagged release as $tagName")
                      ctx.currentProjects.foldLeft(ctx) { (c, p) =>
                        c.updateProject(p.ref)(_.copy(tagName = Some(tagName)))
                      }
                    }
              }
          }
        }
      }
  )

  val pushChanges: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "push-changes",
    check = ctx => {
      val extracted = extract(ctx.state)
      val base      = extracted.get(thisProject).base

      IO.blocking(Vcs.detect(base)).flatMap {
        case None      =>
          IO.raiseError(new RuntimeException(s"No VCS detected at ${base.getAbsolutePath}"))
        case Some(vcs) =>
          for {
            hasUp      <- IO.blocking(vcs.hasUpstream)
            _          <- IO.raiseUnless(hasUp)(
                            new RuntimeException(
                              s"No tracking branch configured for '${vcs.currentBranch}'. " +
                                "Set up a remote tracking branch or remove pushChanges from the release process."
                            )
                          )
            remoteCode <- IO.blocking(vcs.checkRemote(vcs.trackingRemote).!)
            _          <- IO.raiseUnless(remoteCode == 0)(
                            new RuntimeException("Remote check failed. Aborting release.")
                          )
            behind     <- IO.blocking(vcs.isBehindRemote)
            _          <-
              IO.raiseWhen(behind)(
                new RuntimeException(
                  "Upstream has unmerged commits. Merge first or remove pushChanges from process."
                )
              )
          } yield ctx
      }
    },
    action = ctx =>
      required(ctx.vcs, "VCS not initialized") { vcs =>
        IO.blocking(vcs.hasUpstream).flatMap {
          case false => logWarn(ctx, "No upstream branch, changes were NOT pushed.")
          case true  => runProcess(vcs.pushChanges, "vcs push").as(ctx)
        }
      }
  )

}
