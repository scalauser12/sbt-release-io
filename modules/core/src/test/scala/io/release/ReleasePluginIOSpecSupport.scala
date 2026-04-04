package io.release

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.internal.CoreCommandExecution
import io.release.internal.CoreLifecycle
import io.release.internal.CoreStepAliases.Step
import sbt.Project
import sbt.Setting
import sbt.State

import java.io.ByteArrayOutputStream
import java.io.File

trait ReleasePluginIOSpecSupport {

  private val settingsDefaults: Seq[Setting[?]] = Seq(
    ReleaseIO.releaseIOBehaviorCrossBuild                 := false,
    ReleaseIO.releaseIOBehaviorSkipPublish                := false,
    ReleaseIO.releaseIOBehaviorInteractive                := false,
    ReleaseIO.releaseIODefaultsTagExistsAnswer            := None,
    ReleaseIO.releaseIODefaultsSnapshotDependenciesAnswer := None,
    ReleaseIO.releaseIODefaultsRemoteCheckFailureAnswer   := None,
    ReleaseIO.releaseIODefaultsUpstreamBehindAnswer       := None,
    ReleaseIO.releaseIODefaultsPushAnswer                 := None
  ) ++ CoreLifecycle.configDefaultSettings ++ Seq(
    ReleaseIO.releaseIOVcsRemoteCheckTimeout := scala.concurrent.duration.DurationInt(60).seconds
  )

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

  protected object BehaviorOverridePlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseBehaviorOverrides"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def crossBuildEnabled(state: State): Boolean = true

    override protected def skipPublishEnabled(state: State): Boolean = true

    override protected def interactiveEnabled(state: State): Boolean = true
  }

  protected object BaseReleaseSettingsPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseBaseSettings"

    override def resource: Resource[IO, Unit] = Resource.unit

    override lazy val projectSettings: Seq[Setting[?]] =
      baseReleaseSettings ++ Seq(
        ReleaseIO.releaseIOHooksBeforeTag += ReleaseHookIO.action("base-before-tag")(_ => IO.unit)
      )

    def settingsForTests: Seq[Setting[?]] = projectSettings
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

  protected def resolveProcessMode(
      plugin: ReleasePluginIOLike[Unit],
      state: State
  ): IO[CoreCommandExecution.CompiledSteps] =
    CoreCommandExecution.resolveProcessMode(state, plugin.commandRuntime)

  protected def resolveReleaseRun(
      plugin: ReleasePluginIOLike[Unit],
      state: State
  ): IO[CoreCommandExecution.CompiledSteps] =
    CoreCommandExecution.resolveReleaseRun(state, (), plugin.commandRuntime)

  protected def checkStepNames(
      processMode: CoreCommandExecution.CompiledSteps
  ): Seq[String] =
    processMode.steps.map(_.name)

  protected def checkSteps(
      processMode: CoreCommandExecution.CompiledSteps
  ): Seq[Step] =
    processMode.steps

  protected def runStepNames(runProcess: CoreCommandExecution.CompiledSteps): Seq[String] =
    runProcess.steps.map(_.name)

  protected def runSteps(
      runProcess: CoreCommandExecution.CompiledSteps
  ): Seq[Step] =
    runProcess.steps
}
