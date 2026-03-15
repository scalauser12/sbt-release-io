package io.release.monorepo.steps

import cats.effect.IO
import io.release.ReleaseIO.{releaseIONextVersion, releaseIOVersion}
import io.release.internal.SbtRuntime
import io.release.monorepo.internal.MonorepoReleasePlan
import io.release.monorepo.steps.MonorepoStepHelpers.*
import io.release.monorepo.{MonorepoReleaseIO as MR, *}
import io.release.steps.StepHelpers
import sbt.Keys.*
import sbt.{internal as _, *}

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Version-related monorepo release steps: inquire, set, commit. */
private[monorepo] object MonorepoVersionSteps {

  // ── Inlined from MonorepoVersionResolver ──────────────────────────

  private[steps] final case class ResolvedProjectVersion(
      versionFile: File,
      readVersion: File => IO[String],
      versionFileContents: (File, String) => IO[String],
      useGlobalVersion: Boolean
  )

  private[steps] def resolve(state: State, ref: ProjectRef): IO[ResolvedProjectVersion] =
    IO.blocking {
      val runtime = MonorepoRuntime.fromState(state)
      resolve(runtime, ref)
    }

  private def resolve(runtime: MonorepoRuntime, ref: ProjectRef): ResolvedProjectVersion =
    ResolvedProjectVersion(
      versionFile = MonorepoVersionFiles.resolve(runtime, ref),
      readVersion = runtime.readVersion,
      versionFileContents = runtime.versionFileContents,
      useGlobalVersion = runtime.useGlobalVersion
    )

  private[steps] def sessionSettings(state: State): IO[Seq[sbt.Setting[?]]] =
    IO.blocking {
      val runtime = MonorepoRuntime.fromState(state)
      sessionSettings(runtime)
    }

  private def sessionSettings(runtime: MonorepoRuntime): Seq[sbt.Setting[?]] =
    Seq(
      MR.releaseIOMonorepoVersionFile         :=
        runtime.extracted.get(MR.releaseIOMonorepoVersionFile),
      MR.releaseIOMonorepoReadVersion         := runtime.readVersion,
      MR.releaseIOMonorepoVersionFileContents := runtime.versionFileContents,
      MR.releaseIOMonorepoUseGlobalVersion    := runtime.useGlobalVersion
    )

  /** Inquire release and next versions for each project.
    * If the project already has versions pre-populated (from command-line overrides),
    * those are used directly without prompting or computing.
    */
  val inquireVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "inquire-versions",
    validate = (ctx, project) =>
      resolve(ctx.state, project.ref).flatMap { versionInputs =>
        IO.blocking {
          if (!versionInputs.versionFile.exists())
            throw new IllegalStateException(
              s"Version file not found for ${project.name}: ${versionInputs.versionFile.getPath}. " +
                """Create it with contents: version := "0.1.0-SNAPSHOT""""
            )
        }
      },
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
                                                  val (s1, releaseFn) =
                                                    SbtRuntime.runTask(ctx.state, project.ref / releaseIOVersion)
                                                  val (_, nextFn)     =
                                                    SbtRuntime.runTask(s1, project.ref / releaseIONextVersion)
                                                  val useDefaults     =
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
      runtime      <- IO.blocking(MonorepoRuntime.fromState(ctx.state))
      versionInputs = resolve(runtime, project.ref)
      preserved     = sessionSettings(runtime)
      versionFile   = versionInputs.versionFile
      result       <-
        if (
          versionInputs.useGlobalVersion && ctx.state
            .get(MonorepoReleasePlan.globalVersionWrittenKey)
            .flatten
            .contains(ver)
        )
          logInfo(ctx, s"Global version already set to $ver, skipping write for ${project.name}")
        else
          for {
            contents <- versionInputs.versionFileContents(versionFile, ver)
            _        <- IO.blocking {
                          Files.write(versionFile.toPath, contents.getBytes(StandardCharsets.UTF_8))
                        }
            newState <- IO.blocking {
                          val setting   =
                            if (versionInputs.useGlobalVersion) ThisBuild / version := ver
                            else project.ref / version                              := ver
                          val baseState = SbtRuntime.appendWithSession(
                            ctx.state,
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
