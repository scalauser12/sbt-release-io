package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.internal.ProcessStep
import io.release.TestSupport
import sbt.Project
import sbt.ProjectRef
import sbt.Setting
import sbt.State

import java.io.ByteArrayOutputStream
import java.io.File

@scala.annotation.nowarn("cat=deprecation")
trait MonorepoReleasePluginSpecSupport {

  protected final class LoadedState(
      val dir: File,
      val state: State,
      val consoleBuffer: ByteArrayOutputStream
  )

  protected object HookFriendlyPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoHookFriendly"

    override def resource: Resource[IO, Unit] = Resource.unit
  }

  protected abstract class BaseHookFriendlyPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoHookFriendlyInherited"

    override def resource: Resource[IO, Unit] = Resource.unit
  }

  protected object InheritedHookFriendlyPlugin extends BaseHookFriendlyPlugin

  protected object BehaviorOverridePlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoBehaviorOverrides"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def crossBuildEnabled(state: State): Boolean = true

    override protected def skipTestsEnabled(state: State): Boolean = true

    override protected def skipPublishEnabled(state: State): Boolean = true

    override protected def interactiveEnabled(state: State): Boolean = true
  }

  protected abstract class BaseBehaviorOverridePlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoBehaviorOverridesInherited"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def crossBuildEnabled(state: State): Boolean = true

    override protected def skipTestsEnabled(state: State): Boolean = true

    override protected def skipPublishEnabled(state: State): Boolean = true

    override protected def interactiveEnabled(state: State): Boolean = true
  }

  protected object InheritedBehaviorOverridePlugin extends BaseBehaviorOverridePlugin

  protected def resourceAwareHookPlugin(
      observed: Ref[IO, List[String]]
  ): MonorepoReleasePluginLike[Unit] =
    new MonorepoReleasePluginLike[Unit] {
      override def trigger = noTrigger

      override protected def commandName = "releaseMonorepoResourceAwareHooks"

      override def resource: Resource[IO, Unit] =
        Resource.make(observed.update(_ :+ "resource-acquire"))(_ =>
          observed.update(_ :+ "resource-release")
        )

      override protected def monorepoResourceHooks(
          state: State
      ): MonorepoResourceHooks[Unit] =
        MonorepoResourceHooks(
          afterSelectionHooks = Seq(
            MonorepoGlobalResourceHookIO[Unit](
              name = "resource-after-selection",
              execute = _ => ctx => observed.update(_ :+ "resource-global-execute").as(ctx),
              validate = _ => observed.update(_ :+ "resource-global-validate")
            )
          ),
          afterTagHooks = Seq(
            MonorepoProjectResourceHookIO[Unit](
              name = "resource-after-tag",
              execute = _ =>
                (ctx, project) =>
                  observed.update(_ :+ s"resource-project-execute:${project.name}").as(ctx),
              validate =
                (_, project) => observed.update(_ :+ s"resource-project-validate:${project.name}")
            )
          )
        )
    }

  protected def throwingHookSeq(message: String): Seq[MonorepoGlobalHookIO] =
    new scala.collection.immutable.Seq[MonorepoGlobalHookIO] {
      override def iterator: Iterator[MonorepoGlobalHookIO] =
        throw new RuntimeException(message)

      override def apply(idx: Int): MonorepoGlobalHookIO =
        throw new RuntimeException(message)

      override def length: Int =
        throw new RuntimeException(message)
    }

  protected def stateResource(
      prefix: String,
      plugin: MonorepoReleasePluginLike[Unit],
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
    _root_.io.release.internal.MonorepoDefaultSettings.commandAndHookSettings ++ Seq(
      _root_.io.release.ReleaseIO.releaseIOVcsRemoteCheckTimeout := scala.concurrent.duration
        .DurationInt(60)
        .seconds
    )

  protected def resolveProcessMode(
      plugin: MonorepoReleasePluginLike[Unit],
      state: State
  ): IO[MonorepoCommandExecution.CompiledMonorepoSteps] =
    MonorepoCommandExecution.resolveProcessMode(state, plugin.commandRuntime)

  protected def resolveReleaseRun(
      plugin: MonorepoReleasePluginLike[Unit],
      state: State
  ): IO[MonorepoCommandExecution.CompiledMonorepoSteps] =
    MonorepoCommandExecution.resolveReleaseRun(state, (), plugin.commandRuntime)

  protected def checkStepNames(
      processMode: MonorepoCommandExecution.CompiledMonorepoSteps
  ): Seq[String] =
    processMode.steps.map(_.name)

  protected def checkSteps(
      processMode: MonorepoCommandExecution.CompiledMonorepoSteps
  ): Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]] =
    processMode.steps

  protected def runStepNames(
      runProcess: MonorepoCommandExecution.CompiledMonorepoSteps
  ): Seq[String] =
    runProcess.steps.map(_.name)

  protected def runSteps(
      runProcess: MonorepoCommandExecution.CompiledMonorepoSteps
  ): Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]] =
    runProcess.steps

  protected def sampleProject(loaded: LoadedState): ProjectReleaseInfo =
    ProjectReleaseInfo(
      ref = ProjectRef(loaded.dir, "core"),
      name = "core",
      baseDir = loaded.dir,
      versionFile = new File(loaded.dir, "version.sbt"),
      versions = Some(("1.0.0", "1.1.0-SNAPSHOT"))
    )

  protected def sampleContext(
      loaded: LoadedState,
      project: ProjectReleaseInfo
  ): MonorepoContext =
    MonorepoContext(
      state = loaded.state,
      projects = Seq(project)
    )

  protected def runMonorepoCheckSteps(
      steps: Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]],
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Unit] =
    steps
      .filter(step =>
        step.name.startsWith("after-selection:") || step.name.startsWith("after-tag:")
      )
      .foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
        ioCtx.flatMap { current =>
          step match {
            case global: ProcessStep.Single[?]                    =>
              val single = global.asInstanceOf[ProcessStep.Single[MonorepoContext]]
              single.validate(current) *> single.execute(current)
            case perProject: ProcessStep.PerItem[?, ?] @unchecked =>
              val item = perProject
                .asInstanceOf[ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo]]
              item.validate(current, project) *> item.execute(current, project)
          }
        }
      }
      .void

  protected def runMonorepoRunSteps(
      steps: Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]],
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Unit] =
    runMonorepoCheckSteps(steps, ctx, project)
}
