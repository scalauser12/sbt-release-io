package io.release.monorepo.steps

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import _root_.io.release.ReleaseIO.{releaseIONextVersion, releaseIOVersion}
import _root_.io.release.ReleaseKeys
import _root_.io.release.monorepo.MonorepoReleaseIO as MR
import _root_.io.release.steps.StepHelpers
import cats.effect.IO
import io.release.internal.SbtRuntime
import io.release.monorepo.*
import io.release.monorepo.MonorepoReleaseIO.*
import io.release.monorepo.internal.MonorepoReleasePlan
import io.release.monorepo.steps.MonorepoStepHelpers.*
import sbt.{internal => _, *}
import sbt.Keys.*

/** Version-related monorepo release steps: inquire, set, commit. */
private[monorepo] object MonorepoVersionSteps {

  // ── Inlined from MonorepoVersionResolver ──────────────────────────

  private[steps] final case class ResolvedProjectVersion(
      versionFile: File,
      readVersion: File => IO[String],
      writeVersion: (File, String) => IO[String],
      useGlobalVersion: Boolean
  )

  private[steps] def resolve(state: State, ref: ProjectRef): IO[ResolvedProjectVersion] =
    IO.blocking {
      val runtime = MonorepoRuntime.fromState(state)
      ResolvedProjectVersion(
        versionFile = MonorepoVersionFiles.resolve(runtime, ref),
        readVersion = runtime.readVersion,
        writeVersion = runtime.writeVersion,
        useGlobalVersion = runtime.useGlobalVersion
      )
    }

  private[steps] def sessionSettings(state: State): IO[Seq[sbt.Setting[?]]] =
    IO.blocking {
      val runtime      = MonorepoRuntime.fromState(state)
      Seq(
        MR.releaseIOMonorepoVersionFile      :=
          runtime.extracted.get(MR.releaseIOMonorepoVersionFile),
        MR.releaseIOMonorepoReadVersion      := runtime.readVersion,
        MR.releaseIOMonorepoWriteVersion     := runtime.writeVersion,
        MR.releaseIOMonorepoUseGlobalVersion := runtime.useGlobalVersion
      )
    }

  /** Inquire release and next versions for each project.
    * If the project already has versions pre-populated (from command-line overrides),
    * those are used directly without prompting or computing.
    */
  val inquireVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "inquire-versions",
    execute = (ctx, project) =>
      project.versions match {
        case Some((rel, next)) if rel.nonEmpty && next.nonEmpty =>
          resolve(ctx.state, project.ref).flatMap { versionInputs =>
            logInfo(ctx, s"${project.name}: pre-set -> $rel (next: $next)")
              .as(
                ctx.updateProject(project.ref)(
                  _.copy(versionFile = versionInputs.versionFile, versions = Some((rel, next)))
                )
              )
          }
        case _                                                  =>
          resolve(ctx.state, project.ref).flatMap { versionInputs =>
            // In global-version mode with an interactive prompt (not use-defaults),
            // reuse the first project's versions to avoid prompting N times.
            // In non-interactive or use-defaults mode, evaluate each project's version
            // function so that task-derived mismatches are caught by validate-versions.
            val useDefaults =
              StepHelpers.useDefaults(ctx.state)
            if (versionInputs.useGlobalVersion && ctx.interactive && !useDefaults) {
              ctx.currentProjects
                .flatMap(_.versions)
                .find { case (r, n) => r.nonEmpty && n.nonEmpty } match {
                case Some(versions) =>
                  logInfo(
                    ctx,
                    s"${project.name}: reusing global version " +
                      s"${versions._1} (next: ${versions._2})"
                  ).as(
                    ctx.updateProject(project.ref)(
                      _.copy(
                        versionFile = versionInputs.versionFile,
                        versions = Some(versions)
                      )
                    )
                  )
                case None           =>
                  inquireVersionsInteractive(ctx, project)
              }
            } else {
              inquireVersionsInteractive(ctx, project)
            }
          }
      }
  )

  private def inquireVersionsInteractive(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    for {
      versionInputs                          <- resolve(ctx.state, project.ref)
      currentVer                             <- versionInputs.readVersion(versionInputs.versionFile)
      data                                   <- IO.blocking {
                                                  val extracted   = Project.extract(ctx.state)
                                                  val releaseFn   = extracted.get(project.ref / releaseIOVersion)
                                                  val nextFn      = extracted.get(project.ref / releaseIONextVersion)
                                                  val useDefaults =
                                                    StepHelpers.useDefaults(ctx.state)
                                                  (releaseFn(currentVer), nextFn, useDefaults)
                                                }
      (suggestedRelease, nextFn, useDefaults) = data
      releaseVer                             <- promptOrDefault(
                                                  project.releaseVersion,
                                                  suggestedRelease,
                                                  s"Release version for ${project.name}",
                                                  ctx.interactive,
                                                  useDefaults
                                                )
      suggestedNext                           = nextFn(releaseVer)
      nextVer                                <- promptOrDefault(
                                                  project.nextVersion,
                                                  suggestedNext,
                                                  s"Next version for ${project.name}",
                                                  ctx.interactive,
                                                  useDefaults
                                                )
      result                                 <- logInfo(
                                                  ctx,
                                                  s"${project.name}: $currentVer -> $releaseVer (next: $nextVer)"
                                                ).as(
                                                  ctx.updateProject(project.ref)(
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
    execute = ctx =>
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
          ) *> IO
            .blocking(
              ctx.state.log.info(
                "[release-io-monorepo] Version consistency validated for global version mode"
              )
            )
            .as(ctx)
        else IO.pure(ctx)
      }
  )

  /** Write release versions to per-project version files. */
  val setReleaseVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "set-release-version",
    execute = (ctx, project) =>
      project.versions match {
        case Some((releaseVer, _)) => writeProjectVersion(ctx, project, releaseVer)
        case None                  =>
          IO.raiseError(new IllegalStateException(s"Versions not set for ${project.name}"))
      }
  )

  /** Write next snapshot versions to per-project version files. */
  val setNextVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "set-next-version",
    execute = (ctx, project) =>
      project.versions match {
        case Some((_, nextVer)) => writeProjectVersion(ctx, project, nextVer)
        case None               =>
          IO.raiseError(new IllegalStateException(s"Versions not set for ${project.name}"))
      }
  )

  /** Single commit for all release version files. */
  val commitReleaseVersions: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "commit-release-versions",
    execute =
      ctx => commitVersions(ctx, "Setting release versions", { case (releaseVer, _) => releaseVer })
  )

  /** Single commit for all next version files. */
  val commitNextVersions: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "commit-next-versions",
    execute = ctx => commitVersions(ctx, "Setting next versions", { case (_, nextVer) => nextVer })
  )

  // --- private helpers ---

  private def writeProjectVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      ver: String
  ): IO[MonorepoContext] =
    for {
      versionInputs <- resolve(ctx.state, project.ref)
      preserved     <- sessionSettings(ctx.state)
      versionFile    = versionInputs.versionFile
      result        <-
        if (
          versionInputs.useGlobalVersion && ctx.state
            .get(MonorepoReleasePlan.globalVersionWrittenKey)
            .flatten
            .contains(ver)
        )
          logInfo(ctx, s"Global version already set to $ver, skipping write for ${project.name}")
        else
          for {
            contents <- versionInputs.writeVersion(versionFile, ver)
            _        <- IO.blocking {
                          Files.write(versionFile.toPath, contents.getBytes(StandardCharsets.UTF_8))
                        }
            newState <- IO.blocking {
                          val setting       =
                            if (versionInputs.useGlobalVersion) ThisBuild / version := ver
                            else project.ref / version                              := ver
                          val stateWithAttr =
                            ctx.state.put(ReleaseKeys.runtimeVersionOverride, ver)
                          val baseState     = SbtRuntime.appendWithSession(
                            stateWithAttr,
                            preserved ++ Seq(setting)
                          )
                          if (versionInputs.useGlobalVersion)
                            baseState.put(MonorepoReleasePlan.globalVersionWrittenKey, Some(ver))
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
