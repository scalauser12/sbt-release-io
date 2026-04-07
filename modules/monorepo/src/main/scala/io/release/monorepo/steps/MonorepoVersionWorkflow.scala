package io.release.monorepo.steps

import cats.effect.IO
import io.release.ReleasePluginIO.autoImport.releaseIOVersioningNextVersion
import io.release.ReleasePluginIO.autoImport.releaseIOVersioningReleaseVersion
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.internal.VersionWorkflowSupport
import io.release.monorepo.MonorepoReleasePlugin
import io.release.monorepo.steps.MonorepoStepHelpers.*
import io.release.monorepo.*
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
    versionsPairOrFail(project).flatMap { case (releaseVersion, _) =>
      ensureVersionFilesValidated(
        ctx,
        _.hasReleaseVersionFilesValidated,
        _.markReleaseVersionFilesValidated
      ).flatMap(writeProjectVersion(_, project, releaseVersion))
    }

  def writeNextVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    versionsPairOrFail(project).flatMap { case (_, nextVersion) =>
      ensureVersionFilesValidated(
        ctx,
        _.hasNextVersionFilesValidated,
        _.markNextVersionFilesValidated
      ).flatMap(writeProjectVersion(_, project, nextVersion))
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
      versionInputs  <- MonorepoVersionFiles.resolveInputs(ctx.state, project.ref)
      _              <- ensureVersionFileExists(
                          versionInputs,
                          missingVersionFileMessage(
                            project,
                            versionInputs.versionFile,
                            includeConfigurationGuidance = false
                          )
                        )
      currentVersion <- versionInputs.readVersion(versionInputs.versionFile)
      resolvedInputs <- VersionWorkflowSupport.resolveVersionInputsFromTasks(
                          ctx = ctx,
                          currentVersion = currentVersion,
                          releaseVersionTask = project.ref / releaseIOVersioningReleaseVersion,
                          nextVersionTask = project.ref / releaseIOVersioningNextVersion,
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
