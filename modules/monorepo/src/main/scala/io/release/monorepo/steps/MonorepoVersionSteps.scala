package io.release.monorepo.steps

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import cats.effect.IO
import io.release.ReleaseKeys
import io.release.monorepo.*
import io.release.monorepo.MonorepoReleaseIO.*
import io.release.monorepo.internal.MonorepoVersionResolver
import io.release.monorepo.internal.MonorepoInternalKeys
import io.release.monorepo.steps.MonorepoStepHelpers.*
import io.release.internal.SbtRuntime
import sbt.*
import sbt.Keys.*
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
      versionInputs                                        <- MonorepoVersionResolver.resolve(
                                                                ctx.state,
                                                                project.ref
                                                              )
      currentVer                                           <- versionInputs.readVersion(
                                                                versionInputs.versionFile
                                                              )
      data                                                 <- IO.blocking {
                                                                val extracted        =
                                                                  Project.extract(ctx.state)
                                                                val (s1, releaseFn) =
                                                                  extracted.runTask(project.ref / releaseVersion, ctx.state)
                                                                val (s2, nextFn)    =
                                                                  extracted.runTask(project.ref / releaseNextVersion, s1)
                                                                val useDefaults     =
                                                                  s2.get(ReleaseKeys.useDefaults).getOrElse(false)
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
                                                                  .updateProject(project.ref)(
                                                                    _.copy(
                                                                      versionFile = versionInputs.versionFile,
                                                                      versions = Some((releaseVer, nextVer))
                                                                    )
                                                                  )
                                                              )
    } yield result

  /** Validate version consistency in global-version mode (before writing to shared file). */
  val validateVersions: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "validate-versions",
    action = ctx =>
      loadRuntime(ctx).flatMap { runtime =>
        if (runtime.useGlobalVersion)
          validateVersionConsistency(
            ctx.currentProjects,
            { case (rel, _) => rel },
            "Global version mode requires all projects to have the same release version"
          ) *> validateVersionConsistency(
            ctx.currentProjects,
            { case (_, next) => next },
            "Global version mode requires all projects to have the same next version"
          ) *> logInfo(ctx, "Version consistency validated for global version mode")
        else IO.pure(ctx)
      }
  )

  /** Write release versions to per-project version files. */
  val setReleaseVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "set-release-version",
    action = (ctx, project) =>
      project.versions match {
        case Some((releaseVer, _)) => writeProjectVersion(ctx, project, releaseVer)
        case None                  =>
          IO.raiseError(new IllegalStateException(s"Versions not set for ${project.name}"))
      }
  )

  /** Write next snapshot versions to per-project version files. */
  val setNextVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "set-next-version",
    action = (ctx, project) =>
      project.versions match {
        case Some((_, nextVer)) => writeProjectVersion(ctx, project, nextVer)
        case None               =>
          IO.raiseError(new IllegalStateException(s"Versions not set for ${project.name}"))
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
      versionInputs <- MonorepoVersionResolver.resolve(ctx.state, project.ref)
      preserved     <- MonorepoVersionResolver.sessionSettings(ctx.state)
      versionFile    = versionInputs.versionFile
      result     <-
        if (
          versionInputs.useGlobalVersion && ctx.state
            .get(MonorepoInternalKeys.globalVersionWritten)
            .flatten
            .contains(ver)
        )
          logInfo(ctx, s"Global version already set to $ver, skipping write for ${project.name}")
        else
          for {
            contents <- versionInputs.writeVersion(versionFile, ver)
            newState <- IO.blocking {
                          Files.write(versionFile.toPath, contents.getBytes(StandardCharsets.UTF_8))
                          val setting   =
                            if (versionInputs.useGlobalVersion) ThisBuild / version := ver
                            else project.ref / version                        := ver
                          val baseState = SbtRuntime.appendWithSession(
                            ctx.state,
                            preserved ++ Seq(setting)
                          )
                          if (versionInputs.useGlobalVersion)
                            baseState.put(MonorepoInternalKeys.globalVersionWritten, Some(ver))
                          else baseState
                        }
            r        <- logInfo(ctx, s"Wrote version $ver to ${versionFile.getPath} for ${project.name}")
                          .as(
                            ctx
                              .withState(newState)
                              .updateProject(project.ref)(_.copy(versionFile = versionFile))
                          )
          } yield r
    } yield result
}
