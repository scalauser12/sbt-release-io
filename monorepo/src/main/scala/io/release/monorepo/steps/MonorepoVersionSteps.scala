package io.release.monorepo.steps

import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.MonorepoReleaseIO.*
import io.release.ReleaseKeys
import io.release.steps.StepHelpers.*
import sbt.*
import sbt.Keys.*
import sbt.Project.extract
import sbtrelease.ReleasePlugin.autoImport.*

import scala.sys.process.*

/** Version-related monorepo release steps: inquire, set, commit. */
private[monorepo] object MonorepoVersionSteps {

  /** Inquire release and next versions for each project.
    * If the project already has versions pre-populated (from command-line overrides),
    * those are used directly without prompting or computing.
    */
  val inquireVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "inquire-versions",
    action = (ctx, project) => {
      // If both versions were pre-set via command-line overrides, use them directly
      project.versions match {
        case Some((rel, next)) if rel.nonEmpty && next.nonEmpty =>
          IO {
            val currentVer = "pre-set"
            ctx.state.log.info(
              s"[release-io-monorepo] ${project.name}: $currentVer -> $rel (next: $next)"
            )
            ctx.updateProject(project.ref)(_.copy(versions = Some((rel, next))))
          }
        case _                                                  =>
          inquireVersionsInteractive(ctx, project)
      }
    }
  )

  private def inquireVersionsInteractive(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] = {
    val extracted   = extract(ctx.state)
    val readFn      = extracted.get(releaseIOMonorepoReadVersion)
    val versionFile = resolveVersionFile(ctx, project)

    for {
      currentVer                                           <- readFn(versionFile)
      data                                                 <- IO.blocking {
                                                                val (s1, releaseFn) = extracted.runTask(releaseVersion, ctx.state)
                                                                val (s2, nextFn)    = extracted.runTask(releaseNextVersion, s1)
                                                                val useDefaults     = s2.get(ReleaseKeys.useDefaults).getOrElse(false)
                                                                (s2, releaseFn(currentVer), nextFn, useDefaults)
                                                              }
      (updatedState, suggestedRelease, nextFn, useDefaults) = data
      // Use command-line override for release version if available
      releaseVer                                           <- project.versions.map(_._1).filter(_.nonEmpty) match {
                                                                case Some(v) => IO.pure(v)
                                                                case None    =>
                                                                  if (!ctx.interactive || useDefaults) IO.pure(suggestedRelease)
                                                                  else
                                                                    IO.print(
                                                                      s"Release version for ${project.name} [$suggestedRelease] : "
                                                                    ) *> IO.readLine.map { raw =>
                                                                      val input = Option(raw).map(_.trim).getOrElse("")
                                                                      if (input.isEmpty) suggestedRelease else input
                                                                    }
                                                              }
      suggestedNext                                         = nextFn(releaseVer)
      // Use command-line override for next version if available
      nextVer                                              <- project.versions.flatMap(v => Option(v._2).filter(_.nonEmpty)) match {
                                                                case Some(v) => IO.pure(v)
                                                                case None    =>
                                                                  if (!ctx.interactive || useDefaults) IO.pure(suggestedNext)
                                                                  else
                                                                    IO.print(
                                                                      s"Next version for ${project.name} [$suggestedNext] : "
                                                                    ) *> IO.readLine.map { raw =>
                                                                      val input = Option(raw).map(_.trim).getOrElse("")
                                                                      if (input.isEmpty) suggestedNext else input
                                                                    }
                                                              }
      result                                               <- IO {
                                                                ctx.state.log.info(
                                                                  s"[release-io-monorepo] ${project.name}: $currentVer -> $releaseVer (next: $nextVer)"
                                                                )
                                                                ctx
                                                                  .withState(updatedState)
                                                                  .updateProject(project.ref)(_.copy(versions = Some((releaseVer, nextVer))))
                                                              }
    } yield result
  }

  /** Write release versions to per-project version files. */
  val setReleaseVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "set-release-version",
    action = (ctx, project) =>
      project.versions match {
        case Some((releaseVer, _)) => writeProjectVersion(ctx, project, releaseVer)
        case None                  =>
          IO.raiseError(
            new RuntimeException(s"Versions not set for ${project.name}")
          )
      }
  )

  /** Write next snapshot versions to per-project version files. */
  val setNextVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "set-next-version",
    action = (ctx, project) =>
      project.versions match {
        case Some((_, nextVer)) => writeProjectVersion(ctx, project, nextVer)
        case None               =>
          IO.raiseError(
            new RuntimeException(s"Versions not set for ${project.name}")
          )
      }
  )

  /** Single commit for all release version files. */
  val commitReleaseVersions: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "commit-release-versions",
    action = ctx =>
      required(ctx.vcs, "VCS not initialized") { vcs =>
        IO.blocking {
          val extracted = extract(ctx.state)
          val sign      = extracted.get(releaseVcsSign)
          val signOff   = extracted.get(releaseVcsSignOff)
          val base      = vcs.baseDir.getCanonicalFile

          // Stage all version files
          ctx.currentProjects.foreach { project =>
            val versionFile  = resolveVersionFile(ctx, project)
            val relativePath = sbt.IO
              .relativize(base, versionFile.getCanonicalFile)
              .getOrElse(
                throw new RuntimeException(
                  s"Version file [${versionFile.getCanonicalPath}] is outside VCS root [$base]"
                )
              )
            runProcess(vcs.add(relativePath), s"vcs add '$relativePath'")
          }

          val summary = ctx.currentProjects
            .flatMap(p => p.versions.map { case (rel, _) => s"${p.name} $rel" })
            .mkString(", ")

          commitIfChanged(vcs, s"Setting release versions: $summary", sign, signOff, ctx)
        }
      }
  )

  /** Single commit for all next version files. */
  val commitNextVersions: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "commit-next-versions",
    action = ctx =>
      required(ctx.vcs, "VCS not initialized") { vcs =>
        IO.blocking {
          val extracted = extract(ctx.state)
          val sign      = extracted.get(releaseVcsSign)
          val signOff   = extracted.get(releaseVcsSignOff)
          val base      = vcs.baseDir.getCanonicalFile

          // Stage all version files
          ctx.currentProjects.foreach { project =>
            val versionFile  = resolveVersionFile(ctx, project)
            val relativePath = sbt.IO
              .relativize(base, versionFile.getCanonicalFile)
              .getOrElse(
                throw new RuntimeException(
                  s"Version file [${versionFile.getCanonicalPath}] is outside VCS root [$base]"
                )
              )
            runProcess(vcs.add(relativePath), s"vcs add '$relativePath'")
          }

          val summary = ctx.currentProjects
            .flatMap(p => p.versions.map { case (_, next) => s"${p.name} $next" })
            .mkString(", ")

          commitIfChanged(vcs, s"Setting next versions: $summary", sign, signOff, ctx)
        }
      }
  )

  // --- private helpers ---

  private def resolveVersionFile(ctx: MonorepoContext, project: ProjectReleaseInfo): File = {
    val extracted = extract(ctx.state)
    val useGlobal = extracted.get(releaseIOMonorepoUseGlobalVersion)
    if (useGlobal) {
      extracted.get(sbtrelease.ReleasePlugin.autoImport.releaseVersionFile)
    } else {
      val versionFileFn = extracted.get(releaseIOMonorepoVersionFile)
      versionFileFn(project.ref)
    }
  }

  private def writeProjectVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      ver: String
  ): IO[MonorepoContext] = {
    val extracted   = extract(ctx.state)
    val writeFn     = extracted.get(releaseIOMonorepoWriteVersion)
    val versionFile = resolveVersionFile(ctx, project)

    for {
      contents <- writeFn(versionFile, ver)
      result   <- IO.blocking {
                    java.nio.file.Files.write(versionFile.toPath, contents.getBytes("UTF-8"))
                    ctx.state.log.info(
                      s"[release-io-monorepo] Wrote version $ver to ${versionFile.getPath} for ${project.name}"
                    )
                    // Update the sbt state with the new version scoped to this project
                    val newState = extracted.appendWithSession(
                      Seq(project.ref / version := ver),
                      ctx.state
                    )
                    ctx.withState(newState)
                  }
    } yield result
  }

  private def commitIfChanged(
      vcs: sbtrelease.Vcs,
      msg: String,
      sign: Boolean,
      signOff: Boolean,
      ctx: MonorepoContext
  ): MonorepoContext = {
    val statusOutput = {
      val sb   = new StringBuilder
      val code = vcs.status.!(ProcessLogger(line => sb.append(line).append('\n'), _ => ()))
      if (code != 0) throw new RuntimeException(s"vcs status failed with exit code $code")
      sb.toString.trim
    }
    val status       = statusOutput.linesIterator.filterNot(_.startsWith("?")).mkString("\n")

    if (status.nonEmpty) {
      runProcess(vcs.commit(msg, sign, signOff), "vcs commit")
      ctx.state.log.info(s"[release-io-monorepo] Committed: $msg")
    }
    ctx
  }

  private def required[A, B](opt: Option[A], error: String)(f: A => IO[B]): IO[B] =
    opt.fold(IO.raiseError[B](new RuntimeException(error)))(f)
}
