package io.release.runtime.workflow

import cats.effect.IO
import io.release.runtime.ReleaseCtx
import io.release.runtime.ReleaseCtxOps
import io.release.runtime.ReleaseCtxOps.syntax._
import _root_.sbt.TaskKey

import java.io.File

private[release] object VersionWorkflowSupport {

  final case class ResolvedVersionInputs[C <: ReleaseCtx](
      context: C,
      releaseVersion: String,
      nextVersion: String
  )

  def ensureVersionFileExists(versionFile: File, notFoundMessage: String): IO[Unit] =
    VersionFileSupport.ensureExists(versionFile, notFoundMessage)

  def resolveVersionInputsFromTasks[C <: ReleaseCtx: ReleaseCtxOps](
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
      suggestedRelease          = releaseFn(currentVersion)
      releaseData              <- DecisionResolver.resolveVersionInput(
                                    taskCtx,
                                    override_ = releaseVersionOverride,
                                    suggested = suggestedRelease,
                                    logPrefix = logPrefix,
                                    prompt = s"$releaseLabel [$suggestedRelease] : ",
                                    promptContext = releaseLabel,
                                    allowPrompts = allowPrompts,
                                    beforePrompt = beforeReleasePrompt
                                  )
      suggestedNext             = nextFn(releaseData._2)
      nextData                 <- DecisionResolver.resolveVersionInput(
                                    releaseData._1,
                                    override_ = nextVersionOverride,
                                    suggested = suggestedNext,
                                    logPrefix = logPrefix,
                                    prompt = s"$nextLabel [$suggestedNext] : ",
                                    promptContext = nextLabel,
                                    allowPrompts = allowPrompts
                                  )
    } yield ResolvedVersionInputs(
      context = nextData._1,
      releaseVersion = releaseData._2,
      nextVersion = nextData._2
    )

  def writeVersionFile(
      versionFile: File,
      versionValue: String,
      versionFileContents: (File, String) => IO[String]
  ): IO[Unit] =
    for {
      contents <- versionFileContents(versionFile, versionValue)
      _        <- VersionFileSupport.writeUtf8(versionFile, contents)
    } yield ()
}
