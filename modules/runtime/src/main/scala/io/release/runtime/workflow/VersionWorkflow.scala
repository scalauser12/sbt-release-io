package io.release.runtime.workflow

import _root_.sbt.TaskKey
import cats.effect.IO
import io.release.runtime.ReleaseCtx
import io.release.vcs.Vcs

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

private[release] object VersionWorkflow {

  final case class ResolvedVersionInputs[C <: ReleaseCtx](
      context: C,
      releaseVersion: String,
      nextVersion: String
  )

  def resolveVersionInputs[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      currentVersion: String,
      releaseVersionFn: String => String,
      nextVersionFn: String => String,
      releaseVersionOverride: Option[String],
      nextVersionOverride: Option[String],
      logPrefix: String,
      releaseLabel: String,
      nextLabel: String,
      allowPrompts: Boolean,
      beforeReleasePrompt: IO[Unit] = IO.unit
  ): IO[ResolvedVersionInputs[C]] =
    for {
      releaseData                      <- DecisionResolver.resolveVersionInput(
                                            ctx,
                                            override_ = releaseVersionOverride,
                                            suggested = releaseVersionFn(currentVersion),
                                            logPrefix = logPrefix,
                                            promptFor = s => s"$releaseLabel [$s] : ",
                                            promptContext = releaseLabel,
                                            allowPrompts = allowPrompts,
                                            beforePrompt = beforeReleasePrompt
                                          )
      (releaseCtx, releaseVersionValue) = releaseData
      nextData                         <- DecisionResolver.resolveVersionInput(
                                            releaseCtx,
                                            override_ = nextVersionOverride,
                                            suggested = nextVersionFn(releaseVersionValue),
                                            logPrefix = logPrefix,
                                            promptFor = s => s"$nextLabel [$s] : ",
                                            promptContext = nextLabel,
                                            allowPrompts = allowPrompts
                                          )
      (nextCtx, nextVersionValue)       = nextData
    } yield ResolvedVersionInputs(
      context = nextCtx,
      releaseVersion = releaseVersionValue,
      nextVersion = nextVersionValue
    )

  def ensureVersionFileExists(
      versionFile: File,
      notFoundMessage: String
  ): IO[Unit] =
    IO.blocking(versionFile.exists()).flatMap { exists =>
      if (exists) IO.unit
      else IO.raiseError(new IllegalStateException(notFoundMessage))
    }

  /** Reject the release when the version file is matched by a `.gitignore` rule.
    *
    * A gitignored version file is invisible to `git status`, so a write that goes through
    * with no commit would leave a silently corrupted on-disk state that can poison later
    * releases. Used by both core and monorepo workflows at validate time, just before the
    * on-disk write, and on the no-op commit path.
    */
  def assertVersionFileNotIgnored(
      actionName: String,
      versionPath: String,
      vcs: Vcs
  ): IO[Unit] =
    vcs.isIgnored(versionPath).flatMap {
      case false => IO.unit
      case true  =>
        IO.raiseError(
          new IllegalStateException(
            s"$actionName: version file `$versionPath` is matched by a .gitignore rule, " +
              "so the release cannot commit a version bump for it. Remove the matching " +
              "pattern from `.gitignore` (or `.git/info/exclude`) before re-running the " +
              "release."
          )
        )
    }

  def resolveVersionInputsFromTasks[C <: ReleaseCtx { type Self = C }](
      ctx: C,
      currentVersion: String,
      releaseVersionTask: TaskKey[String => String],
      nextVersionTask: TaskKey[String => String],
      releaseVersionOverride: Option[String],
      nextVersionOverride: Option[String],
      logPrefix: String,
      releaseLabel: String,
      nextLabel: String,
      allowPrompts: Boolean,
      actionName: String = "inquire-versions",
      beforeReleasePrompt: IO[Unit] = IO.unit
  ): IO[ResolvedVersionInputs[C]] =
    for {
      releaseTaskData          <-
        StepHelpers.runTaskChecked(ctx.state, releaseVersionTask, actionName)
      (releaseState, releaseFn) = releaseTaskData
      nextTaskData             <-
        StepHelpers.runTaskChecked(releaseState, nextVersionTask, actionName)
      (nextState, nextFn)       = nextTaskData
      taskCtx                   = ctx.withState(nextState)
      resolvedInputs           <- resolveVersionInputs(
                                    ctx = taskCtx,
                                    currentVersion = currentVersion,
                                    releaseVersionFn = releaseFn,
                                    nextVersionFn = nextFn,
                                    releaseVersionOverride = releaseVersionOverride,
                                    nextVersionOverride = nextVersionOverride,
                                    logPrefix = logPrefix,
                                    releaseLabel = releaseLabel,
                                    nextLabel = nextLabel,
                                    allowPrompts = allowPrompts,
                                    beforeReleasePrompt = beforeReleasePrompt
                                  )
    } yield resolvedInputs

  def writeVersionFile(
      versionFile: File,
      versionValue: String,
      versionFileContents: (File, String) => IO[String]
  ): IO[Unit] =
    for {
      contents <- versionFileContents(versionFile, versionValue)
      _        <- IO
                    .blocking(
                      Files.write(versionFile.toPath, contents.getBytes(StandardCharsets.UTF_8))
                    )
                    .void
    } yield ()

  def wouldChangeVersionFile(
      versionFile: File,
      versionValue: String,
      versionFileContents: (File, String) => IO[String]
  ): IO[Boolean] =
    for {
      currentContents  <- IO.blocking(
                            Files.readString(versionFile.toPath, StandardCharsets.UTF_8)
                          )
      renderedContents <- versionFileContents(versionFile, versionValue)
    } yield currentContents != renderedContents
}
