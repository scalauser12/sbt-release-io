package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.TestSupport
import io.release.monorepo.steps.MonorepoReleaseSteps
import sbt.Project
import sbt.ProjectRef
import sbt.Setting
import sbt.State

import java.io.ByteArrayOutputStream
import java.io.File
import scala.annotation.nowarn

@nowarn("cat=deprecation")
trait MonorepoReleasePluginLegacySpecSupport {

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

  protected object CustomCheckProcessPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoLegacyCheckProcess"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def monorepoReleaseCheckProcess(state: State): Seq[MonorepoStepIO] =
      super.monorepoReleaseCheckProcess(state) :+
        MonorepoStepIO
          .global("custom-check-preflight")
          .validateOnly
  }

  protected object CustomReleaseProcessPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoLegacyReleaseProcess"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
      super.monorepoReleaseProcess(state) :+
        MonorepoStepIO
          .globalResource[Unit]("custom-release-step")
          .executeAction(_ => _ => IO.unit)
  }

  protected object SameLengthReleaseProcessPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoLegacySameLength"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
      super.monorepoReleaseProcess(state).dropRight(1) :+
        MonorepoStepIO
          .globalResource[Unit]("custom-release-replacement")
          .executeAction(_ => _ => IO.unit)
  }

  protected object SameNameReleaseProcessPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoLegacySameName"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
      super.monorepoReleaseProcess(state).dropRight(1) :+
        MonorepoStepIO
          .globalResource[Unit](MonorepoReleaseSteps.defaults.last.name)
          .executeAction(_ => _ => IO.unit)
  }

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
    Seq(
      MonorepoReleaseIO.releaseIOMonorepoProcess                         := MonorepoReleaseSteps.defaults,
      MonorepoReleaseIO.releaseIOMonorepoEnableSnapshotDependenciesCheck := true,
      MonorepoReleaseIO.releaseIOMonorepoEnableRunClean                  := true,
      MonorepoReleaseIO.releaseIOMonorepoEnableRunTests                  := true,
      MonorepoReleaseIO.releaseIOMonorepoEnableTagging                   := true,
      MonorepoReleaseIO.releaseIOMonorepoEnablePublish                   := true,
      MonorepoReleaseIO.releaseIOMonorepoEnablePush                      := true,
      io.release.ReleaseIO.releaseIOVcsRemoteCheckTimeout                := scala.concurrent.duration
        .DurationInt(60)
        .seconds,
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks            := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterSelectionHooks             := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforeVersionResolutionHooks    := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterVersionResolutionHooks     := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforeReleaseVersionWriteHooks  := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterReleaseVersionWriteHooks   := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforeReleaseCommitHooks        := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterReleaseCommitHooks         := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforeTagHooks                  := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterTagHooks                   := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforePublishHooks              := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterPublishHooks               := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforeNextVersionWriteHooks     := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterNextVersionWriteHooks      := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforeNextCommitHooks           := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterNextCommitHooks            := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforePushHooks                 := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterPushHooks                  := Seq.empty
    )

  protected def resolveProcessMode(
      plugin: MonorepoReleasePluginLike[Unit],
      state: State
  ): IO[MonorepoCommandExecution.ResolvedProcessMode[Unit]] =
    MonorepoCommandExecution.resolveProcessMode(state, plugin.commandRuntime)

  protected def resolveReleaseRun(
      plugin: MonorepoReleasePluginLike[Unit],
      state: State,
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): IO[MonorepoCommandExecution.ResolvedReleaseRun] =
    MonorepoCommandExecution.resolveReleaseRun(state, processMode, (), plugin.commandRuntime)

  protected def logLegacyModeWarning(
      state: State,
      result: MonorepoCommandExecution.LegacyResult
  ): IO[Unit] =
    MonorepoCommandExecution.logLegacyModeWarning(state, result)

  protected def legacyMode(result: MonorepoCommandExecution.LegacyResult): Boolean =
    result.legacyMode

  protected def legacyReasons(result: MonorepoCommandExecution.LegacyResult): Seq[String] =
    result.legacyReasons

  protected def checkLegacyMode(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Boolean =
    legacyMode(processMode.checkLegacy)

  protected def checkLegacyReasons(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    legacyReasons(processMode.checkLegacy)

  protected def releaseLegacyMode(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Boolean =
    legacyMode(processMode.releaseLegacy)

  protected def releaseLegacyReasons(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    legacyReasons(processMode.releaseLegacy)

  protected def checkStepNames(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    processMode.checkSteps.map(_.name)

  protected def checkSteps(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[MonorepoStepIO] =
    processMode.checkSteps

  protected def releaseStepNames(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    processMode.releaseSteps.map(_(()).name)

  protected def runStepNames(
      runProcess: MonorepoCommandExecution.ResolvedReleaseRun
  ): Seq[String] =
    runProcess.steps.map(_.name)

  protected def runSteps(
      runProcess: MonorepoCommandExecution.ResolvedReleaseRun
  ): Seq[MonorepoStepIO] =
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
      steps: Seq[MonorepoStepIO],
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
            case global: MonorepoStepIO.Global         =>
              global.validate(current) *> global.execute(current)
            case perProject: MonorepoStepIO.PerProject =>
              perProject.validate(current, project) *> perProject.execute(current, project)
          }
        }
      }
      .void

  protected def runMonorepoRunSteps(
      steps: Seq[MonorepoStepIO],
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Unit] =
    runMonorepoCheckSteps(steps, ctx, project)
}
