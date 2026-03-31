package io.release

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.internal.CoreCommandExecution
import sbt.Project
import sbt.Setting
import sbt.State

import java.io.ByteArrayOutputStream
import java.io.File

trait ReleasePluginIOSpecSupport {

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
  ): IO[CoreCommandExecution.ResolvedProcessMode] =
    CoreCommandExecution.resolveProcessMode(state, plugin.commandRuntime)

  protected def resolveReleaseRun(
      plugin: ReleasePluginIOLike[Unit],
      state: State
  ): IO[CoreCommandExecution.ResolvedReleaseRun] =
    CoreCommandExecution.resolveReleaseRun(state, (), plugin.commandRuntime)

  protected def checkStepNames(
      processMode: CoreCommandExecution.ResolvedProcessMode
  ): Seq[String] =
    processMode.checkSteps.map(_.name)

  protected def checkSteps(
      processMode: CoreCommandExecution.ResolvedProcessMode
  ): Seq[ReleaseStepIO] =
    processMode.checkSteps

  protected def runStepNames(runProcess: CoreCommandExecution.ResolvedReleaseRun): Seq[String] =
    runProcess.steps.map(_.name)

  protected def runSteps(runProcess: CoreCommandExecution.ResolvedReleaseRun): Seq[ReleaseStepIO] =
    runProcess.steps
}
