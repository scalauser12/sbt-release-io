package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.TestSupport
import io.release.internal.SbtRuntime
import io.release.monorepo.steps.MonorepoReleaseSteps
import munit.CatsEffectSuite
import sbt.Project
import sbt.Setting
import sbt.State
import sbt.internal.util.AttributeMap
import sbt.internal.util.ConsoleOut
import sbt.internal.util.GlobalLogging
import sbt.internal.util.MainAppender

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import scala.annotation.nowarn

import sbt.ProjectRef

@nowarn("cat=deprecation")
class MonorepoReleasePluginLegacyModeSpec extends CatsEffectSuite {

  test("resolveProcessMode - treat raw process customization as legacy mode and ignore hooks") {
    val rawProcess                = Seq(
      MonorepoReleaseSteps.initializeVcs,
      MonorepoReleaseSteps.resolveReleaseOrder,
      MonorepoReleaseSteps.detectOrSelectProjects
    )
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoProcess    := rawProcess,
      MonorepoReleaseIO.releaseIOMonorepoEnablePush := false,
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource("monorepo-plugin-legacy-raw", MonorepoReleasePlugin, settings).use { loaded =>
      for {
        processMode <- resolveProcessMode(MonorepoReleasePlugin, loaded.state)
        _            = assertEquals(checkLegacyMode(processMode), true)
        _            = assertEquals(releaseLegacyMode(processMode), true)
        _            = assert(
                         checkLegacyReasons(processMode)
                           .contains("`releaseIOMonorepoProcess` differs from defaults")
                       )
        _            = assertEquals(checkStepNames(processMode), rawProcess.map(_.name))
        _            = assert(
                         !checkStepNames(processMode).exists(_.startsWith("before-selection:"))
                       )
        _           <- logLegacyModeWarning(loaded.state, processMode.checkLegacy)
        log         <- IO.blocking(loaded.consoleBuffer.toString("UTF-8"))
      } yield {
        assert(log.contains("Legacy raw process mode enabled"))
        assert(
          log.contains(
            "Hook/policy compilation is bypassed while legacy raw process mode is active."
          )
        )
      }
    }
  }

  test("resolveProcessMode - keep the default plugin on compiled hook mode") {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource("monorepo-plugin-compiled-hooks", MonorepoReleasePlugin, settings).use { loaded =>
      resolveProcessMode(MonorepoReleasePlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
      }
    }
  }

  test(
    "resolveProcessMode - validate resource-aware global and per-project hooks during check without acquiring resource"
  ) {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        MonorepoReleaseIO.releaseIOMonorepoAfterSelectionHooks +=
          MonorepoGlobalHookIO
            .action("plain-after-selection")(_ => observed.update(_ :+ "plain-global-execute")),
        MonorepoReleaseIO.releaseIOMonorepoAfterTagHooks +=
          MonorepoProjectHookIO.action("plain-after-tag")((_, project) =>
            observed.update(_ :+ s"plain-project-execute:${project.name}")
          )
      )

      stateResource("monorepo-plugin-resource-check", plugin, settings).use { loaded =>
        val project = sampleProject(loaded)
        val ctx     = sampleContext(loaded, project)

        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assertEquals(checkLegacyMode(processMode), false)
          _            = assert(
                           checkStepNames(processMode).contains("after-selection:plain-after-selection")
                         )
          _            = assert(
                           checkStepNames(processMode)
                             .contains("after-selection:resource-after-selection")
                         )
          _            = assert(checkStepNames(processMode).contains("after-tag:plain-after-tag"))
          _            = assert(checkStepNames(processMode).contains("after-tag:resource-after-tag"))
          _           <- runMonorepoCheckSteps(checkSteps(processMode), ctx, project)
          events      <- observed.get
        } yield assertEquals(
          events,
          List(
            "plain-global-execute",
            "resource-global-validate",
            s"plain-project-execute:${project.name}",
            s"resource-project-validate:${project.name}"
          )
        )
      }
    }
  }

  test(
    "resolveReleaseRun - execute resource-aware global and per-project hooks without legacy mode"
  ) {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        MonorepoReleaseIO.releaseIOMonorepoAfterSelectionHooks +=
          MonorepoGlobalHookIO
            .action("plain-after-selection")(_ => observed.update(_ :+ "plain-global-execute")),
        MonorepoReleaseIO.releaseIOMonorepoAfterTagHooks +=
          MonorepoProjectHookIO.action("plain-after-tag")((_, project) =>
            observed.update(_ :+ s"plain-project-execute:${project.name}")
          )
      )

      stateResource("monorepo-plugin-resource-run", plugin, settings).use { loaded =>
        val project = sampleProject(loaded)
        val ctx     = sampleContext(loaded, project)

        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assertEquals(releaseLegacyMode(processMode), false)
          _           <- plugin.resource.use { _ =>
                           for {
                             runProcess <- resolveReleaseRun(plugin, loaded.state, processMode)
                             _           = assertEquals(legacyMode(runProcess), false)
                             _           = assert(
                                             runStepNames(runProcess)
                                               .contains("after-selection:resource-after-selection")
                                           )
                             _           = assert(
                                             runStepNames(runProcess)
                                               .contains("after-tag:resource-after-tag")
                                           )
                             _          <- runMonorepoRunSteps(
                                             runSteps(runProcess),
                                             ctx,
                                             project
                                           )
                           } yield ()
                         }
          events      <- observed.get
        } yield assertEquals(
          events,
          List(
            "resource-acquire",
            "plain-global-execute",
            "resource-global-validate",
            "resource-global-execute",
            s"plain-project-execute:${project.name}",
            s"resource-project-validate:${project.name}",
            s"resource-project-execute:${project.name}",
            "resource-release"
          )
        )
      }
    }
  }

  test("resolveReleaseRun - omit resource-aware hooks when the phase is disabled") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        MonorepoReleaseIO.releaseIOMonorepoEnableTagging := false
      )

      stateResource("monorepo-plugin-resource-disabled-phase", plugin, settings).use { loaded =>
        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assertEquals(releaseLegacyMode(processMode), false)
          _            = assert(!checkStepNames(processMode).exists(_.startsWith("after-tag:")))
          _           <- plugin.resource.use { _ =>
                           resolveReleaseRun(plugin, loaded.state, processMode).map { runProcess =>
                             assertEquals(legacyMode(runProcess), false)
                             assert(!runStepNames(runProcess).exists(_.startsWith("after-tag:")))
                           }
                         }
        } yield ()
      }
    }
  }

  test("resolveProcessMode - bypass resource-aware hooks while legacy raw process mode is active") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val rawProcess                = Seq(
        MonorepoReleaseSteps.initializeVcs,
        MonorepoReleaseSteps.resolveReleaseOrder,
        MonorepoReleaseSteps.detectOrSelectProjects
      )
      val settings: Seq[Setting[?]] = Seq(MonorepoReleaseIO.releaseIOMonorepoProcess := rawProcess)

      stateResource("monorepo-plugin-resource-legacy-bypass", plugin, settings).use { loaded =>
        resolveProcessMode(plugin, loaded.state).flatMap { processMode =>
          for {
            _      <- IO(assertEquals(checkLegacyMode(processMode), true))
            _      <- IO(assertEquals(releaseLegacyMode(processMode), true))
            _      <- IO(assertEquals(checkStepNames(processMode), rawProcess.map(_.name)))
            _      <-
              IO(
                assert(
                  !checkStepNames(processMode).contains("after-selection:resource-after-selection")
                )
              )
            events <- observed.get
          } yield assertEquals(events, Nil)
        }
      }
    }
  }

  test(
    "resolveProcessMode - keep a direct custom plugin with unrelated overrides on compiled hook mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource("monorepo-plugin-custom-compiled-hooks", HookFriendlyPlugin, settings).use {
      loaded =>
        resolveProcessMode(HookFriendlyPlugin, loaded.state).map { processMode =>
          assertEquals(checkLegacyMode(processMode), false)
          assertEquals(releaseLegacyMode(processMode), false)
          assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
        }
    }
  }

  test(
    "resolveProcessMode - keep an inherited custom plugin with unrelated overrides on compiled hook mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource(
      "monorepo-plugin-inherited-compiled-hooks",
      InheritedHookFriendlyPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(InheritedHookFriendlyPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
      }
    }
  }

  test(
    "resolveProcessMode - do not evaluate hook settings while legacy raw process mode is active"
  ) {
    val rawProcess                = Seq(
      MonorepoReleaseSteps.initializeVcs,
      MonorepoReleaseSteps.resolveReleaseOrder,
      MonorepoReleaseSteps.detectOrSelectProjects
    )
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks := throwingHookSeq("hook boom")
    )

    stateResource(
      "monorepo-plugin-legacy-throwing-hooks",
      MonorepoReleasePlugin,
      Seq(MonorepoReleaseIO.releaseIOMonorepoProcess := rawProcess)
    ).use { loaded =>
      val updatedState =
        SbtRuntime.extracted(loaded.state).appendWithSession(settings, loaded.state)

      resolveProcessMode(MonorepoReleasePlugin, updatedState).map { processMode =>
        assertEquals(checkLegacyMode(processMode), true)
        assertEquals(releaseLegacyMode(processMode), true)
        assertEquals(checkStepNames(processMode), rawProcess.map(_.name))
      }
    }
  }

  test("resolveProcessMode - treat custom check-process wiring as legacy mode") {
    stateResource("monorepo-plugin-legacy-check-process", CustomCheckProcessPlugin).use { loaded =>
      resolveProcessMode(CustomCheckProcessPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), true)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(
          checkLegacyReasons(processMode)
            .contains(
              "`monorepoReleaseCheckProcess` differs from the configured raw process"
            )
        )
        assert(checkStepNames(processMode).contains("custom-check-preflight"))
      }
    }
  }

  test("resolveReleaseRun - keep custom check-process wiring on compiled hook mode") {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource("monorepo-plugin-check-process-run", CustomCheckProcessPlugin, settings).use {
      loaded =>
        for {
          processMode <- resolveProcessMode(CustomCheckProcessPlugin, loaded.state)
          runProcess  <- resolveReleaseRun(CustomCheckProcessPlugin, loaded.state, processMode)
        } yield {
          assertEquals(checkLegacyMode(processMode), true)
          assertEquals(releaseLegacyMode(processMode), false)
          assert(checkStepNames(processMode).contains("custom-check-preflight"))
          assertEquals(legacyMode(runProcess), false)
          assert(runStepNames(runProcess).contains("before-selection:before-selection-hook"))
          assert(!runStepNames(runProcess).contains("custom-check-preflight"))
        }
    }
  }

  test("resolveProcessMode - treat custom release-process wiring as legacy mode") {
    stateResource("monorepo-plugin-legacy-release-process", CustomReleaseProcessPlugin).use {
      loaded =>
        resolveProcessMode(CustomReleaseProcessPlugin, loaded.state).map { processMode =>
          assertEquals(checkLegacyMode(processMode), false)
          assertEquals(releaseLegacyMode(processMode), true)
          assert(
            releaseLegacyReasons(processMode)
              .contains(
                "`monorepoReleaseProcess` differs from the configured raw process"
              )
          )
          assert(releaseStepNames(processMode).contains("custom-release-step"))
        }
    }
  }

  test(
    "resolveProcessMode - keep same-length custom release-process wiring on compiled check mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource(
      "monorepo-plugin-same-length-check",
      SameLengthReleaseProcessPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(SameLengthReleaseProcessPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
      }
    }
  }

  test(
    "resolveReleaseRun - treat same-length custom release-process wiring as legacy on the run path"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource(
      "monorepo-plugin-same-length-run",
      SameLengthReleaseProcessPlugin,
      settings
    ).use { loaded =>
      for {
        processMode <- resolveProcessMode(SameLengthReleaseProcessPlugin, loaded.state)
        runProcess  <- resolveReleaseRun(SameLengthReleaseProcessPlugin, loaded.state, processMode)
      } yield {
        assertEquals(legacyMode(runProcess), true)
        assert(
          legacyReasons(runProcess)
            .contains("`monorepoReleaseProcess` differs from the configured raw process")
        )
        assert(runStepNames(runProcess).contains("custom-release-replacement"))
        assert(!runStepNames(runProcess).exists(_.startsWith("before-selection:")))
      }
    }
  }

  test(
    "resolveProcessMode - keep same-name custom release-process wiring on compiled check mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource(
      "monorepo-plugin-same-name-check",
      SameNameReleaseProcessPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(SameNameReleaseProcessPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
      }
    }
  }

  test(
    "resolveReleaseRun - treat same-name custom release-process wiring as legacy on the run path"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks +=
        MonorepoGlobalHookIO.action("before-selection-hook")(_ => IO.unit)
    )

    stateResource(
      "monorepo-plugin-same-name-run",
      SameNameReleaseProcessPlugin,
      settings
    ).use { loaded =>
      for {
        processMode <- resolveProcessMode(SameNameReleaseProcessPlugin, loaded.state)
        runProcess  <- resolveReleaseRun(SameNameReleaseProcessPlugin, loaded.state, processMode)
      } yield {
        val rawSteps = SbtRuntime
          .extracted(loaded.state)
          .get(MonorepoReleaseIO.releaseIOMonorepoProcess)
        assertEquals(legacyMode(runProcess), true)
        assert(
          legacyReasons(runProcess)
            .contains("`monorepoReleaseProcess` differs from the configured raw process")
        )
        assertEquals(runStepNames(runProcess).toList, rawSteps.map(_.name).toList)
        assertNotEquals(runSteps(runProcess), rawSteps)
        assert(!runStepNames(runProcess).exists(_.startsWith("before-selection:")))
      }
    }
  }

  private object HookFriendlyPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoHookFriendly"

    override def resource: Resource[IO, Unit] = Resource.unit
  }

  private abstract class BaseHookFriendlyPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoHookFriendlyInherited"

    override def resource: Resource[IO, Unit] = Resource.unit
  }

  private object InheritedHookFriendlyPlugin extends BaseHookFriendlyPlugin

  private object CustomCheckProcessPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoLegacyCheckProcess"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def monorepoReleaseCheckProcess(state: State): Seq[MonorepoStepIO] =
      super.monorepoReleaseCheckProcess(state) :+
        MonorepoStepIO
          .global("custom-check-preflight")
          .validateOnly
  }

  private object CustomReleaseProcessPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoLegacyReleaseProcess"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
      super.monorepoReleaseProcess(state) :+
        MonorepoStepIO
          .globalResource[Unit]("custom-release-step")
          .executeAction(_ => _ => IO.unit)
  }

  private object SameLengthReleaseProcessPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoLegacySameLength"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
      super.monorepoReleaseProcess(state).dropRight(1) :+
        MonorepoStepIO
          .globalResource[Unit]("custom-release-replacement")
          .executeAction(_ => _ => IO.unit)
  }

  private object SameNameReleaseProcessPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoLegacySameName"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
      super.monorepoReleaseProcess(state).dropRight(1) :+
        MonorepoStepIO
          .globalResource[Unit](MonorepoReleaseSteps.defaults.last.name)
          .executeAction(_ => _ => IO.unit)
  }

  private def resourceAwareHookPlugin(
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

  private def throwingHookSeq(message: String): Seq[MonorepoGlobalHookIO] =
    new scala.collection.immutable.Seq[MonorepoGlobalHookIO] {
      override def iterator: Iterator[MonorepoGlobalHookIO] =
        throw new RuntimeException(message)

      override def apply(idx: Int): MonorepoGlobalHookIO =
        throw new RuntimeException(message)

      override def length: Int =
        throw new RuntimeException(message)
    }

  private final class LoadedState(
      val dir: File,
      val state: State,
      val consoleBuffer: ByteArrayOutputStream
  )

  private object LoadedState {
    def apply(
        dir: File,
        state: State,
        consoleBuffer: ByteArrayOutputStream
    ): LoadedState =
      new LoadedState(dir, state, consoleBuffer)
  }

  private def stateResource(
      prefix: String,
      plugin: MonorepoReleasePluginLike[Unit],
      rootSettings: Seq[Setting[?]] = Nil
  ): Resource[IO, LoadedState] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val logFile       = new File(dir, "sbt-test.log")
        val consoleBuffer = new ByteArrayOutputStream()
        val consoleOut    = ConsoleOut.printStreamOut(new PrintStream(consoleBuffer))
        val globalLogging =
          GlobalLogging.initial(
            MainAppender.globalDefault(consoleOut),
            logFile,
            consoleOut
          )
        val baseState     = State(
          configuration = TestSupport.dummyAppConfiguration(dir),
          definedCommands = Nil,
          exitHooks = Set.empty,
          onFailure = None,
          remainingCommands = Nil,
          history = State.newHistory,
          attributes = AttributeMap.empty,
          globalLogging = globalLogging,
          currentCommand = None,
          next = State.Continue
        )
        val state         = sbt.TestBuildState(
          baseState = baseState,
          baseDir = dir,
          projects = Seq(
            Project("root", dir).settings((settingsDefaults ++ rootSettings)*)
          ),
          currentProjectId = Some("root")
        )

        LoadedState(dir, state, consoleBuffer)
      }
    }

  private def settingsDefaults: Seq[Setting[?]] =
    Seq(
      MonorepoReleaseIO.releaseIOMonorepoProcess                         := MonorepoReleaseSteps.defaults,
      MonorepoReleaseIO.releaseIOMonorepoEnableSnapshotDependenciesCheck := true,
      MonorepoReleaseIO.releaseIOMonorepoEnableRunClean                  := true,
      MonorepoReleaseIO.releaseIOMonorepoEnableRunTests                  := true,
      MonorepoReleaseIO.releaseIOMonorepoEnableTagging                   := true,
      MonorepoReleaseIO.releaseIOMonorepoEnablePublish                   := true,
      MonorepoReleaseIO.releaseIOMonorepoEnablePush                      := true,
      io.release.ReleaseIO.releaseIOVcsRemoteCheckTimeout                := scala.concurrent.duration
        .DurationInt(
          60
        )
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

  private def resolveProcessMode(
      plugin: MonorepoReleasePluginLike[Unit],
      state: State
  ): IO[MonorepoCommandExecution.ResolvedProcessMode[Unit]] =
    MonorepoCommandExecution.resolveProcessMode(state, plugin.commandRuntime)

  private def resolveReleaseRun(
      plugin: MonorepoReleasePluginLike[Unit],
      state: State,
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): IO[MonorepoCommandExecution.ResolvedReleaseRun] =
    MonorepoCommandExecution.resolveReleaseRun(
      state,
      processMode,
      (),
      plugin.commandRuntime
    )

  private def logLegacyModeWarning(
      state: State,
      result: MonorepoCommandExecution.LegacyResult
  ): IO[Unit] =
    MonorepoCommandExecution.logLegacyModeWarning(state, result)

  private def legacyMode(result: MonorepoCommandExecution.LegacyResult): Boolean =
    result.legacyMode

  private def legacyReasons(result: MonorepoCommandExecution.LegacyResult): Seq[String] =
    result.legacyReasons

  private def checkLegacyMode(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Boolean =
    legacyMode(processMode.checkLegacy)

  private def checkLegacyReasons(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    legacyReasons(processMode.checkLegacy)

  private def releaseLegacyMode(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Boolean =
    legacyMode(processMode.releaseLegacy)

  private def releaseLegacyReasons(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    legacyReasons(processMode.releaseLegacy)

  private def checkStepNames(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    processMode.checkSteps.map(_.name)

  private def checkSteps(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[MonorepoStepIO] =
    processMode.checkSteps

  private def releaseStepNames(
      processMode: MonorepoCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    processMode.releaseSteps.map(_(()).name)

  private def runStepNames(
      runProcess: MonorepoCommandExecution.ResolvedReleaseRun
  ): Seq[String] =
    runProcess.steps.map(_.name)

  private def runSteps(
      runProcess: MonorepoCommandExecution.ResolvedReleaseRun
  ): Seq[MonorepoStepIO] =
    runProcess.steps

  private def sampleProject(loaded: LoadedState): ProjectReleaseInfo =
    ProjectReleaseInfo(
      ref = ProjectRef(loaded.dir, "core"),
      name = "core",
      baseDir = loaded.dir,
      versionFile = new File(loaded.dir, "version.sbt"),
      versions = Some(("1.0.0", "1.1.0-SNAPSHOT"))
    )

  private def sampleContext(
      loaded: LoadedState,
      project: ProjectReleaseInfo
  ): MonorepoContext =
    MonorepoContext(
      state = loaded.state,
      projects = Seq(project)
    )

  private def runMonorepoCheckSteps(
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

  private def runMonorepoRunSteps(
      steps: Seq[MonorepoStepIO],
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[Unit] =
    runMonorepoCheckSteps(steps, ctx, project)
}
