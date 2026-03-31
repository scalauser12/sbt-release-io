package io.release

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.internal.CoreCommandExecution
import io.release.steps.ReleaseSteps
import sbt.Project
import sbt.Setting
import sbt.State

import java.io.ByteArrayOutputStream
import java.io.File
import scala.annotation.nowarn

@nowarn("cat=deprecation")
trait ReleasePluginIOLegacySpecSupport {

  protected final class LoadedState(
      val dir: File,
      val state: State,
      val consoleBuffer: ByteArrayOutputStream
  )

  protected object HookFriendlyPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseHookFriendly"

    override def resource: Resource[IO, Unit] = Resource.unit
  }

  protected abstract class BaseHookFriendlyPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseHookFriendlyInherited"

    override def resource: Resource[IO, Unit] = Resource.unit
  }

  protected object InheritedHookFriendlyPlugin extends BaseHookFriendlyPlugin

  protected object CustomCheckProcessPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseLegacyCheckProcess"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def releaseCheckProcess(state: State): Seq[ReleaseStepIO] =
      super.releaseCheckProcess(state) :+
        ReleaseStepIO
          .step("custom-check-preflight")
          .validateOnly
  }

  protected object CustomReleaseProcessPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseLegacyReleaseProcess"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def releaseProcess(state: State): Seq[Unit => ReleaseStepIO] =
      super.releaseProcess(state) :+
        ReleaseStepIO
          .resourceStep[Unit]("custom-release-step")
          .executeAction(_ => _ => IO.unit)
  }

  protected object SameLengthReleaseProcessPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseLegacySameLength"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def releaseProcess(state: State): Seq[Unit => ReleaseStepIO] =
      super.releaseProcess(state).dropRight(1) :+
        ReleaseStepIO
          .resourceStep[Unit]("custom-release-replacement")
          .executeAction(_ => _ => IO.unit)
  }

  protected object SameNameReleaseProcessPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseLegacySameName"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def releaseProcess(state: State): Seq[Unit => ReleaseStepIO] =
      super.releaseProcess(state).dropRight(1) :+
        ReleaseStepIO
          .resourceStep[Unit](ReleaseSteps.defaults.last.name)
          .executeAction(_ => _ => IO.unit)
  }

  protected def resourceAwareHookPlugin(
      observed: Ref[IO, List[String]]
  ): ReleasePluginIOLike[Unit] =
    new ReleasePluginIOLike[Unit] {
      override def trigger = noTrigger

      override protected def commandName = "releaseResourceAwareHooks"

      override def resource: Resource[IO, Unit] =
        Resource.make(observed.update(_ :+ "resource-acquire"))(_ =>
          observed.update(_ :+ "resource-release")
        )

      override protected def releaseResourceHooks(state: State): ReleaseResourceHooks[Unit] =
        ReleaseResourceHooks(
          beforeTagHooks = Seq(
            ReleaseResourceHookIO[Unit](
              name = "resource-before-tag",
              execute = _ => ctx => observed.update(_ :+ "resource-execute").as(ctx),
              validate = _ => observed.update(_ :+ "resource-validate")
            )
          )
        )
    }

  protected def throwingHookSeq(message: String): Seq[ReleaseHookIO] =
    new scala.collection.immutable.Seq[ReleaseHookIO] {
      override def iterator: Iterator[ReleaseHookIO] =
        throw new RuntimeException(message)

      override def apply(idx: Int): ReleaseHookIO =
        throw new RuntimeException(message)

      override def length: Int =
        throw new RuntimeException(message)
    }

  protected def stateResource(
      prefix: String,
      plugin: ReleasePluginIOLike[Unit],
      rootSettings: Seq[Setting[?]] = Nil
  ): Resource[IO, LoadedState] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val buffered = TestSupport.bufferedState(dir)
        val state    = sbt.TestBuildState(
          baseState = buffered.state,
          baseDir = dir,
          projects = Seq(
            Project("root", dir).settings((settingsDefaults ++ rootSettings)*)
          ),
          currentProjectId = Some("root")
        )

        new LoadedState(dir, state, buffered.consoleBuffer)
      }
    }

  protected def settingsDefaults: Seq[Setting[?]] =
    Seq(
      ReleaseIO.releaseIOProcess                         := ReleaseSteps.defaults,
      ReleaseIO.releaseIOEnableSnapshotDependenciesCheck := true,
      ReleaseIO.releaseIOEnableRunClean                  := true,
      ReleaseIO.releaseIOEnableRunTests                  := true,
      ReleaseIO.releaseIOEnableTagging                   := true,
      ReleaseIO.releaseIOEnablePublish                   := true,
      ReleaseIO.releaseIOEnablePush                      := true,
      ReleaseIO.releaseIOVcsRemoteCheckTimeout           := scala.concurrent.duration.DurationInt(60).seconds,
      ReleaseIO.releaseIOAfterCleanCheckHooks            := Seq.empty,
      ReleaseIO.releaseIOBeforeVersionResolutionHooks    := Seq.empty,
      ReleaseIO.releaseIOAfterVersionResolutionHooks     := Seq.empty,
      ReleaseIO.releaseIOBeforeReleaseVersionWriteHooks  := Seq.empty,
      ReleaseIO.releaseIOAfterReleaseVersionWriteHooks   := Seq.empty,
      ReleaseIO.releaseIOBeforeReleaseCommitHooks        := Seq.empty,
      ReleaseIO.releaseIOAfterReleaseCommitHooks         := Seq.empty,
      ReleaseIO.releaseIOBeforeTagHooks                  := Seq.empty,
      ReleaseIO.releaseIOAfterTagHooks                   := Seq.empty,
      ReleaseIO.releaseIOBeforePublishHooks              := Seq.empty,
      ReleaseIO.releaseIOAfterPublishHooks               := Seq.empty,
      ReleaseIO.releaseIOBeforeNextVersionWriteHooks     := Seq.empty,
      ReleaseIO.releaseIOAfterNextVersionWriteHooks      := Seq.empty,
      ReleaseIO.releaseIOBeforeNextCommitHooks           := Seq.empty,
      ReleaseIO.releaseIOAfterNextCommitHooks            := Seq.empty,
      ReleaseIO.releaseIOBeforePushHooks                 := Seq.empty,
      ReleaseIO.releaseIOAfterPushHooks                  := Seq.empty
    )

  protected def resolveProcessMode(
      plugin: ReleasePluginIOLike[Unit],
      state: State
  ): IO[CoreCommandExecution.ResolvedProcessMode[Unit]] =
    CoreCommandExecution.resolveProcessMode(state, plugin.commandRuntime)

  protected def resolveReleaseRun(
      plugin: ReleasePluginIOLike[Unit],
      state: State,
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): IO[CoreCommandExecution.ResolvedReleaseRun] =
    CoreCommandExecution.resolveReleaseRun(state, processMode, (), plugin.commandRuntime)

  protected def logLegacyModeWarning(
      state: State,
      result: CoreCommandExecution.LegacyResult
  ): IO[Unit] =
    CoreCommandExecution.logLegacyModeWarning(state, result)

  protected def legacyMode(result: CoreCommandExecution.LegacyResult): Boolean =
    result.legacyMode

  protected def legacyReasons(result: CoreCommandExecution.LegacyResult): Seq[String] =
    result.legacyReasons

  protected def checkLegacyMode(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Boolean =
    legacyMode(processMode.checkLegacy)

  protected def checkLegacyReasons(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    legacyReasons(processMode.checkLegacy)

  protected def releaseLegacyMode(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Boolean =
    legacyMode(processMode.releaseLegacy)

  protected def releaseLegacyReasons(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    legacyReasons(processMode.releaseLegacy)

  protected def checkStepNames(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    processMode.checkSteps.map(_.name)

  protected def checkSteps(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[ReleaseStepIO] =
    processMode.checkSteps

  protected def releaseStepNames(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    processMode.releaseSteps.map(_(()).name)

  protected def runStepNames(runProcess: CoreCommandExecution.ResolvedReleaseRun): Seq[String] =
    runProcess.steps.map(_.name)

  protected def runSteps(runProcess: CoreCommandExecution.ResolvedReleaseRun): Seq[ReleaseStepIO] =
    runProcess.steps
}
