package io.release.monorepo.steps

import cats.effect.IO
import io.release.ReleaseIO.releaseIONextVersion
import io.release.ReleaseIO.releaseIOVersion
import io.release.internal.SbtRuntime
import io.release.monorepo.steps.MonorepoStepHelpers.*
import io.release.monorepo.{MonorepoReleaseIO as MR, *}
import io.release.steps.StepHelpers
import io.release.steps.StepHelpers.parseVersionInput
import sbt.Keys.*
import sbt.{internal as _, *}

import java.nio.charset.StandardCharsets
import java.nio.file.Files

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
    project.versions match {
      case Some((releaseVersion, nextVersion)) if releaseVersion.nonEmpty && nextVersion.nonEmpty =>
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
      case _                                                                                      =>
        resolveProjectVersions(ctx, project, allowPrompts = true).flatMap { resolved =>
          logInfo(
            ctx,
            s"${project.name}: ${resolved.currentVersion} -> " +
              s"${resolved.releaseVersion} " +
              s"(next: ${resolved.nextVersion})"
          ).as(withResolvedVersions(ctx, project.ref, resolved))
        }
    }

  def writeReleaseVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    versionsPairOrFail(project).flatMap { case (releaseVersion, _) =>
      writeProjectVersion(ctx, project, releaseVersion)
    }

  def writeNextVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    versionsPairOrFail(project).flatMap { case (_, nextVersion) =>
      writeProjectVersion(ctx, project, nextVersion)
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
  ): IO[ResolvedProjectVersions] =
    for {
      versionInputs                                 <- MonorepoVersionFiles.resolveInputs(ctx.state, project.ref)
      _                                             <- ensureVersionFileExists(
                                                         versionInputs,
                                                         missingVersionFileMessage(
                                                           project,
                                                           versionInputs.versionFile,
                                                           includeConfigurationGuidance = false
                                                         )
                                                       )
      currentVersion                                <- versionInputs.readVersion(versionInputs.versionFile)
      data                                          <- IO.blocking {
                                                         val (updatedState, releaseVersionFn) =
                                                           SbtRuntime.runTask(
                                                             ctx.state,
                                                             project.ref / releaseIOVersion
                                                           )
                                                         val (_, nextVersionFn)               =
                                                           SbtRuntime.runTask(
                                                             updatedState,
                                                             project.ref / releaseIONextVersion
                                                           )
                                                         val useDefaults                      =
                                                           StepHelpers.useDefaults(ctx)

                                                         (
                                                           releaseVersionFn(currentVersion),
                                                           nextVersionFn,
                                                           useDefaults
                                                         )
                                                       }
      (suggestedRelease, nextVersionFn, useDefaults) = data
      releaseVersion                                <- promptOrDefault(
                                                         project.releaseVersion.filter(_.nonEmpty),
                                                         suggestedRelease,
                                                         s"Release version for ${project.name}",
                                                         ctx.interactive,
                                                         useDefaults,
                                                         allowPrompts = allowPrompts
                                                       )
      nextVersion                                   <- promptOrDefault(
                                                         project.nextVersion.filter(_.nonEmpty),
                                                         nextVersionFn(releaseVersion),
                                                         s"Next version for ${project.name}",
                                                         ctx.interactive,
                                                         useDefaults,
                                                         allowPrompts = allowPrompts
                                                       )
    } yield ResolvedProjectVersions(
      versionFile = versionInputs.versionFile,
      currentVersion = currentVersion,
      releaseVersion = releaseVersion,
      nextVersion = nextVersion
    )

  private def versionsPairOrFail(
      project: ProjectReleaseInfo
  ): IO[(String, String)] =
    project.versions match {
      case Some(pair) => IO.pure(pair)
      case None       =>
        IO.raiseError(new IllegalStateException(s"Versions not set for ${project.name}"))
    }

  private def ensureVersionFileExists(
      versionInputs: MonorepoVersionFiles.VersionInputs,
      notFoundMessage: String
  ): IO[Unit] =
    IO.blocking(versionInputs.versionFile.exists()).flatMap { exists =>
      if (exists) IO.unit
      else IO.raiseError(new IllegalStateException(notFoundMessage))
    }

  /** Fail fast when any configured monorepo projects resolve to the same physical version file.
    * Runs at write time (inside `writeProjectVersion`) so it sees the current state after any
    * late-bound steps that may have mutated `releaseIOMonorepoVersionFile`. Checks all projects
    * from `releaseIOMonorepoProjects`, not just the selected subset, so that a partial release
    * still detects a shared file that would be mutated for unreleased siblings.
    */
  private def validateDistinctVersionFiles(runtime: MonorepoRuntime): IO[Unit] = {
    val entries = runtime.extracted.get(MR.releaseIOMonorepoProjects).map { ref =>
      ref.project -> MonorepoVersionFiles.resolve(runtime, ref).getCanonicalPath
    }
    val byPath  = entries.groupBy(_._2).filter(_._2.length > 1)

    if (byPath.isEmpty) IO.unit
    else {
      val details = byPath
        .map { case (path, projects) =>
          s"${projects.map(_._1).mkString(", ")} -> $path"
        }
        .mkString("; ")

      IO.raiseError(
        new IllegalStateException(
          "Multiple projects resolve to the same version file: " + details + ". " +
            "Each monorepo project needs its own version file. " +
            "If you are upgrading from global version mode, " +
            "create per-project version.sbt files and remove any " +
            "`ThisBuild / releaseIOVersionFile` override. " +
            "See `releaseIOMonorepo help` for setup guidance."
        )
      )
    }
  }

  private def writeProjectVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      versionValue: String
  ): IO[MonorepoContext] =
    for {
      runtime      <- IO.blocking(MonorepoRuntime.fromState(ctx.state))
      _            <- validateDistinctVersionFiles(runtime)
      versionInputs = MonorepoVersionFiles.resolveInputs(runtime, project.ref)
      preserved     = MonorepoVersionFiles.sessionSettings(runtime)
      versionFile   = versionInputs.versionFile
      contents     <- versionInputs.versionFileContents(versionFile, versionValue)
      _            <- IO.blocking {
                        Files.write(
                          versionFile.toPath,
                          contents.getBytes(StandardCharsets.UTF_8)
                        )
                      }
      newState     <- IO.blocking {
                        SbtRuntime.appendWithSession(
                          ctx.state,
                          preserved ++ Seq(project.ref / version := versionValue)
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

  /** Resolve a version from an override, a default, or an interactive prompt. */
  private def promptOrDefault(
      override_ : Option[String],
      suggested: String,
      label: String,
      interactive: Boolean,
      useDefaults: Boolean,
      allowPrompts: Boolean
  ): IO[String] =
    override_.filter(_.nonEmpty) match {
      case Some(versionValue) => parseVersionInput(versionValue, versionValue)
      case None               =>
        if (!interactive || useDefaults || !allowPrompts) IO.pure(suggested)
        else
          IO.print(s"$label [$suggested] : ") *>
            IO.readLine.flatMap(parseVersionInput(_, suggested))
    }

  private def missingVersionFileMessage(
      project: ProjectReleaseInfo,
      versionFile: File,
      includeConfigurationGuidance: Boolean
  ): String = {
    val prefix = s"Version file not found for ${project.name}: ${versionFile.getPath}. "

    if (includeConfigurationGuidance)
      prefix +
        "Create it with contents like `version := \"0.1.0-SNAPSHOT\"`, or configure " +
        "`releaseIOMonorepoVersionFile`, `releaseIOMonorepoReadVersion`, and " +
        "`releaseIOMonorepoVersionFileContents`. See `releaseIOMonorepo help`."
    else prefix + "See `releaseIOMonorepo help`."
  }
}
