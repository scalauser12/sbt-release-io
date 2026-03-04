package io.release.monorepo.steps

import cats.effect.IO
import io.release.ReleaseKeys
import io.release.monorepo.*
import io.release.monorepo.MonorepoReleaseIO.*
import io.release.monorepo.steps.MonorepoStepHelpers.*
import sbt.*
import sbt.Keys.*
import sbt.Project.extract
import sbtrelease.ReleasePlugin.autoImport.*

/** Version-related monorepo release steps: inquire, set, commit. */
private[monorepo] object MonorepoVersionSteps {

  /** Inquire release and next versions for each project.
    * If the project already has versions pre-populated (from command-line overrides),
    * those are used directly without prompting or computing.
    */
  val inquireVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "inquire-versions",
    action = (ctx, project) =>
      project.versions match {
        case Some((rel, next)) if rel.nonEmpty && next.nonEmpty =>
          logInfo(ctx, s"${project.name}: pre-set -> $rel (next: $next)")
            .as(ctx.updateProject(project.ref)(_.copy(versions = Some((rel, next)))))
        case _                                                  =>
          inquireVersionsInteractive(ctx, project)
      }
  )

  private def inquireVersionsInteractive(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    for {
      versionFile                                          <- resolveVersionFile(ctx, project)
      setup                                                <- IO.blocking {
                                                                val extracted = extract(ctx.state)
                                                                val readFn    = extracted.get(releaseIOMonorepoReadVersion)
                                                                (extracted, readFn)
                                                              }
      (extracted, readFn)                                   = setup
      currentVer                                           <- readFn(versionFile)
      data                                                 <- IO.blocking {
                                                                val (s1, releaseFn) = extracted.runTask(releaseVersion, ctx.state)
                                                                val (s2, nextFn)    = extracted.runTask(releaseNextVersion, s1)
                                                                val useDefaults     = s2.get(ReleaseKeys.useDefaults).getOrElse(false)
                                                                (s2, releaseFn(currentVer), nextFn, useDefaults)
                                                              }
      (updatedState, suggestedRelease, nextFn, useDefaults) = data
      releaseVer                                           <- promptOrDefault(
                                                                project.releaseVersion,
                                                                suggestedRelease,
                                                                s"Release version for ${project.name}",
                                                                ctx.interactive,
                                                                useDefaults
                                                              )
      suggestedNext                                         = nextFn(releaseVer)
      nextVer                                              <- promptOrDefault(
                                                                project.nextVersion,
                                                                suggestedNext,
                                                                s"Next version for ${project.name}",
                                                                ctx.interactive,
                                                                useDefaults
                                                              )
      result                                               <- logInfo(
                                                                ctx,
                                                                s"${project.name}: $currentVer -> $releaseVer (next: $nextVer)"
                                                              ).as(
                                                                ctx
                                                                  .withState(updatedState)
                                                                  .updateProject(project.ref)(_.copy(versions = Some((releaseVer, nextVer))))
                                                              )
    } yield result

  /** Write release versions to per-project version files. */
  val setReleaseVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "set-release-version",
    action = (ctx, project) =>
      project.versions match {
        case Some((releaseVer, _)) => writeProjectVersion(ctx, project, releaseVer)
        case None                  =>
          IO.raiseError(new RuntimeException(s"Versions not set for ${project.name}"))
      }
  )

  /** Write next snapshot versions to per-project version files. */
  val setNextVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "set-next-version",
    action = (ctx, project) =>
      project.versions match {
        case Some((_, nextVer)) => writeProjectVersion(ctx, project, nextVer)
        case None               =>
          IO.raiseError(new RuntimeException(s"Versions not set for ${project.name}"))
      }
  )

  /** Single commit for all release version files. */
  val commitReleaseVersions: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "commit-release-versions",
    action =
      ctx => commitVersions(ctx, "Setting release versions", { case (releaseVer, _) => releaseVer })
  )

  /** Single commit for all next version files. */
  val commitNextVersions: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "commit-next-versions",
    action = ctx => commitVersions(ctx, "Setting next versions", { case (_, nextVer) => nextVer })
  )

  // --- private helpers ---

  private def writeProjectVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      ver: String
  ): IO[MonorepoContext] =
    for {
      versionFile         <- resolveVersionFile(ctx, project)
      setup               <- IO.blocking {
                               val extracted = extract(ctx.state)
                               val writeFn   = extracted.get(releaseIOMonorepoWriteVersion)
                               (extracted, writeFn)
                             }
      (extracted, writeFn) = setup
      contents            <- writeFn(versionFile, ver)
      newState            <- IO.blocking {
                               java.nio.file.Files.write(versionFile.toPath, contents.getBytes("UTF-8"))
                               extracted.appendWithSession(
                                 Seq(project.ref / version := ver),
                                 ctx.state
                               )
                             }
      result              <- logInfo(ctx, s"Wrote version $ver to ${versionFile.getPath} for ${project.name}")
                               .as(ctx.withState(newState))
    } yield result
}
