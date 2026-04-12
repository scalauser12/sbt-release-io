package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.LoadCompat
import io.release.ReleaseSharedDefaultSettingsSupport
import io.release.ReleaseSharedKeys.releaseIOVersioningBump
import io.release.ReleaseSharedKeys.releaseIOVersioningNextVersion
import io.release.ReleaseSharedKeys.releaseIOVersioningReleaseVersion
import io.release.monorepo.*
import io.release.monorepo.MonorepoReleasePlugin
import io.release.monorepo.internal.*
import io.release.monorepo.internal.steps.MonorepoStepHelpers.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.StepHelpers
import io.release.runtime.workflow.VersionWorkflowSupport
import io.release.version.Version
import sbt.Keys.*
import sbt.{internal as _, *}

/** Shared internal workflow for monorepo version-resolution and version-file writes.
  *
  * [[MonorepoVersionSteps]] keeps the public step definitions and names; this helper owns the
  * underlying version-resolution mechanics.
  */
private[monorepo] object MonorepoVersionWorkflow {

  final case class ResolvedProjectVersions(
      versionFile: File,
      currentVersion: String,
      releaseVersion: String,
      nextVersion: String
  )

  private def missingVersionTaskWarning(
      projectRef: ProjectRef,
      taskKey: TaskKey[?],
      fallback: String
  ): String =
    s"${projectRef.project}: ${taskKey.key.label} is undefined; falling back to $fallback"

  def validateInquireVersions(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Unit] =
    MonorepoVersionFiles.resolveInputs(ctx.state, project.ref).flatMap { versionInputs =>
      ensureVersionFileExists(
        versionInputs,
        missingVersionFileMessage(
          project,
          versionInputs.versionFile,
          includeConfigurationGuidance = true
        )
      )
    }

  def inquireVersions(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    project.resolvedVersions match {
      case Some((releaseVersion, nextVersion)) =>
        MonorepoVersionFiles.resolveInputs(ctx.state, project.ref).flatMap { versionInputs =>
          logInfo(ctx, s"${project.name}: pre-set -> $releaseVersion (next: $nextVersion)")
            .as(
              withResolvedVersions(
                ctx,
                project.ref,
                ResolvedProjectVersions(
                  versionFile = versionInputs.versionFile,
                  currentVersion = "",
                  releaseVersion = releaseVersion,
                  nextVersion = nextVersion
                )
              )
            )
        }
      case _                                   =>
        resolveProjectVersions(ctx, project, allowPrompts = true).flatMap {
          case (updatedCtx, resolved) =>
            logInfo(
              updatedCtx,
              s"${project.name}: ${resolved.currentVersion} -> " +
                s"${resolved.releaseVersion} " +
                s"(next: ${resolved.nextVersion})"
            ).as(withResolvedVersions(updatedCtx, project.ref, resolved))
        }
    }

  def writeReleaseVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    writeVersionFromPair(
      ctx,
      project,
      _._1,
      _.hasReleaseVersionFilesValidated,
      _.markReleaseVersionFilesValidated
    )

  def writeNextVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    writeVersionFromPair(
      ctx,
      project,
      _._2,
      _.hasNextVersionFilesValidated,
      _.markNextVersionFilesValidated
    )

  private def writeVersionFromPair(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      selectVersion: ((String, String)) => String,
      hasValidated: MonorepoContext => Boolean,
      markValidated: MonorepoContext => MonorepoContext
  ): IO[MonorepoContext] =
    versionsPairOrFail(project).flatMap { versions =>
      ensureVersionFilesValidated(ctx, hasValidated, markValidated)
        .flatMap(writeProjectVersion(_, project, selectVersion(versions)))
    }

  def withResolvedVersions(
      ctx: MonorepoContext,
      projectRef: ProjectRef,
      resolved: ResolvedProjectVersions
  ): MonorepoContext =
    ctx.updateProject(projectRef)(
      _.copy(
        versionFile = resolved.versionFile,
        versions = Some(resolved.releaseVersion -> resolved.nextVersion)
      )
    )

  def resolveProjectVersions(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      allowPrompts: Boolean
  ): IO[(MonorepoContext, ResolvedProjectVersions)] =
    for {
      versionInputs          <- MonorepoVersionFiles.resolveInputs(ctx.state, project.ref)
      _                      <- ensureVersionFileExists(
                                  versionInputs,
                                  missingVersionFileMessage(
                                    project,
                                    versionInputs.versionFile,
                                    includeConfigurationGuidance = false
                                  )
                                )
      currentVersion         <- versionInputs.readVersion(versionInputs.versionFile)
      releaseData            <- resolveVersionFunction(
                                  ctx,
                                  project.ref,
                                  releaseIOVersioningReleaseVersion,
                                  ReleaseSharedDefaultSettingsSupport.defaultReleaseVersionTask,
                                  "inquire-versions"
                                )
      (releaseCtx, releaseFn) = releaseData
      nextData               <- resolveVersionFunction(
                                  releaseCtx,
                                  project.ref,
                                  releaseIOVersioningNextVersion,
                                  ReleaseSharedDefaultSettingsSupport.defaultNextVersionTask,
                                  "inquire-versions"
                                )
      (taskCtx, nextFn)       = nextData
      resolvedInputs         <- VersionWorkflowSupport.resolveVersionInputs(
                                  ctx = taskCtx,
                                  currentVersion = currentVersion,
                                  releaseVersionFn = releaseFn,
                                  nextVersionFn = nextFn,
                                  releaseVersionOverride = project.releaseVersion,
                                  nextVersionOverride = project.nextVersion,
                                  logPrefix = ReleaseLogPrefixes.Monorepo,
                                  releaseLabel = s"Release version for ${project.name}",
                                  nextLabel = s"Next version for ${project.name}",
                                  allowPrompts = allowPrompts
                                )
    } yield (
      resolvedInputs.context,
      ResolvedProjectVersions(
        versionFile = versionInputs.versionFile,
        currentVersion = currentVersion,
        releaseVersion = resolvedInputs.releaseVersion,
        nextVersion = resolvedInputs.nextVersion
      )
    )

  private def versionsPairOrFail(
      project: ProjectReleaseInfo
  ): IO[(String, String)] =
    project.resolvedVersions match {
      case Some(pair) => IO.pure(pair)
      case None       =>
        IO.raiseError(new IllegalStateException(s"Resolved versions not set for ${project.name}"))
    }

  private def resolveVersionFunction(
      ctx: MonorepoContext,
      projectRef: ProjectRef,
      taskKey: TaskKey[String => String],
      defaultForBump: Version.Bump => (String => String),
      actionName: String
  ): IO[(MonorepoContext, String => String)] =
    if (LoadCompat.containsScopedKey(ctx.state, projectRef / taskKey))
      StepHelpers
        .runTaskChecked(ctx.state, projectRef / taskKey, actionName)
        .map { case (nextState, fn) =>
          (ctx.withState(nextState), fn)
        }
    else
      logWarn(
        ctx,
        missingVersionTaskWarning(
          projectRef,
          taskKey,
          s"built-in defaults from ${releaseIOVersioningBump.key.label}"
        )
      ) *>
        resolveVersionBump(ctx, projectRef, actionName).map { case (bumpCtx, bump) =>
          (bumpCtx, defaultForBump(bump))
        }

  private def resolveVersionBump(
      ctx: MonorepoContext,
      projectRef: ProjectRef,
      actionName: String
  ): IO[(MonorepoContext, Version.Bump)] =
    if (LoadCompat.containsScopedKey(ctx.state, projectRef / releaseIOVersioningBump))
      StepHelpers
        .runTaskChecked(ctx.state, projectRef / releaseIOVersioningBump, actionName)
        .map { case (nextState, bump) =>
          (ctx.withState(nextState), bump)
        }
    else
      logWarn(
        ctx,
        missingVersionTaskWarning(
          projectRef,
          releaseIOVersioningBump,
          s"${Version.Bump.default}"
        )
      ).as((ctx, Version.Bump.default))

  private def ensureVersionFileExists(
      versionInputs: MonorepoVersionFiles.VersionInputs,
      notFoundMessage: String
  ): IO[Unit] =
    VersionWorkflowSupport.ensureVersionFileExists(versionInputs.versionFile, notFoundMessage)

  /** Fail fast when any configured monorepo projects resolve to the same physical version file.
    * Runs once at the start of each version-write phase so it sees the current state after any
    * late-bound steps that may have mutated `releaseIOMonorepoVersioningFile`. Checks all projects
    * from `releaseIOMonorepoSelectionProjects`, not just the selected subset, so that a partial release
    * still detects a shared file that would be mutated for unreleased siblings.
    */
  private def validateDistinctVersionFiles(runtime: MonorepoRuntime): IO[Unit] =
    IO.blocking {
      val entries = runtime.extracted
        .get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects)
        .map { ref =>
          ref.project -> MonorepoVersionFiles.resolve(runtime, ref).getCanonicalPath
        }
      val byPath  = entries.groupBy(_._2).filter(_._2.length > 1)

      byPath.headOption.map { _ =>
        byPath
          .map { case (path, projects) =>
            s"${projects.map(_._1).mkString(", ")} -> $path"
          }
          .mkString("; ")
      }
    }.flatMap {
      case Some(details) =>
        IO.raiseError(
          new IllegalStateException(
            "Multiple projects resolve to the same version file: " + details + ". " +
              "Each monorepo project needs its own version file. " +
              "If you are upgrading from global version mode, " +
              "create per-project version.sbt files and remove any " +
              "`ThisBuild / releaseIOVersioningFile` override. " +
              "See `releaseIOMonorepo help` for setup guidance."
          )
        )
      case None          => IO.unit
    }

  private def ensureVersionFilesValidated(
      ctx: MonorepoContext,
      alreadyValidated: MonorepoContext => Boolean,
      markValidated: MonorepoContext => MonorepoContext
  ): IO[MonorepoContext] =
    if (alreadyValidated(ctx)) IO.pure(ctx)
    else
      IO.blocking(MonorepoRuntime.fromState(ctx.state)).flatMap { runtime =>
        validateDistinctVersionFiles(runtime).as(markValidated(ctx))
      }

  private def writeProjectVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      versionValue: String
  ): IO[MonorepoContext] =
    for {
      runtime      <- IO.blocking(MonorepoRuntime.fromState(ctx.state))
      versionInputs = MonorepoVersionFiles.resolveInputs(runtime, project.ref)
      preserved    <- MonorepoVersionFiles.preservedSettings(
                        ctx.state,
                        ctx.currentProjects.map(_.ref)
                      )
      versionFile   = versionInputs.versionFile
      _            <- VersionWorkflowSupport.writeVersionFile(
                        versionFile,
                        versionValue,
                        versionInputs.versionFileContents
                      )
      newState     <- IO.blocking {
                        SbtRuntime.appendWithSession(
                          ctx.state,
                          preserved ++ Seq(
                            project.ref / version := versionValue
                          )
                        )
                      }
      updated       = ctx
                        .withState(newState)
                        .updateProject(project.ref)(_.copy(versionFile = versionFile))
      _            <- logInfo(
                        updated,
                        s"Wrote version $versionValue to ${versionFile.getPath} for ${project.name}"
                      )
    } yield updated

  private def missingVersionFileMessage(
      project: ProjectReleaseInfo,
      versionFile: File,
      includeConfigurationGuidance: Boolean
  ): String = {
    val prefix = s"Version file not found for ${project.name}: ${versionFile.getPath}. "

    if (includeConfigurationGuidance)
      prefix +
        "Create it with contents like `version := \"0.1.0-SNAPSHOT\"`, or configure " +
        "`releaseIOMonorepoVersioningFile`, `releaseIOMonorepoVersioningReadVersion`, and " +
        "`releaseIOMonorepoVersioningFileContents`. See `releaseIOMonorepo help`."
    else prefix + "See `releaseIOMonorepo help`."
  }
}
