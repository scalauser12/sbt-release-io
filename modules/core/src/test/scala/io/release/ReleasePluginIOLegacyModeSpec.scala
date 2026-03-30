package io.release

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.internal.CoreCommandExecution
import io.release.internal.SbtRuntime
import io.release.steps.ReleaseSteps
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

@nowarn("cat=deprecation")
class ReleasePluginIOLegacyModeSpec extends CatsEffectSuite {

  test("resolveProcessMode - treat raw process customization as legacy mode and ignore hooks") {
    val rawProcess                = Seq(ReleaseSteps.initializeVcs, ReleaseSteps.inquireVersions)
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOProcess    := rawProcess,
      ReleaseIO.releaseIOEnablePush := false,
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource("release-plugin-legacy-raw", ReleasePluginIO, settings).use { loaded =>
      for {
        processMode <- resolveProcessMode(ReleasePluginIO, loaded.state)
        _            = assertEquals(checkLegacyMode(processMode), true)
        _            = assertEquals(releaseLegacyMode(processMode), true)
        _            = assert(
                         checkLegacyReasons(processMode)
                           .contains("`releaseIOProcess` differs from defaults")
                       )
        _            = assertEquals(checkStepNames(processMode), rawProcess.map(_.name))
        _            = assert(!checkStepNames(processMode).exists(_.startsWith("before-tag:")))
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
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource("release-plugin-compiled-hooks", ReleasePluginIO, settings).use { loaded =>
      resolveProcessMode(ReleasePluginIO, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
      }
    }
  }

  test(
    "resolveProcessMode - validate resource-aware hooks during check without acquiring resource"
  ) {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        ReleaseIO.releaseIOBeforeTagHooks +=
          ReleaseHookIO.action("plain-before-tag")(_ => observed.update(_ :+ "plain-execute"))
      )

      stateResource("release-plugin-resource-check", plugin, settings).use { loaded =>
        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assertEquals(checkLegacyMode(processMode), false)
          _            = assert(checkStepNames(processMode).contains("before-tag:plain-before-tag"))
          _            = assert(checkStepNames(processMode).contains("before-tag:resource-before-tag"))
          ctx          = ReleaseContext(state = loaded.state)
          _           <- checkSteps(processMode)
                           .filter(_.name.startsWith("before-tag:"))
                           .foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
                             ioCtx
                               .flatTap(current => step.validate(current))
                               .flatMap(step.execute)
                           }
                           .void
          events      <- observed.get
        } yield assertEquals(events, List("plain-execute", "resource-validate"))
      }
    }
  }

  test("resolveReleaseRun - execute resource-aware hooks after plain hooks without legacy mode") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val settings: Seq[Setting[?]] = Seq(
        ReleaseIO.releaseIOBeforeTagHooks +=
          ReleaseHookIO.action("plain-before-tag")(_ => observed.update(_ :+ "plain-execute"))
      )

      stateResource("release-plugin-resource-run", plugin, settings).use { loaded =>
        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assertEquals(releaseLegacyMode(processMode), false)
          _           <- plugin.resource.use { _ =>
                           for {
                             runProcess <- resolveReleaseRun(plugin, loaded.state, processMode)
                             _           = assertEquals(legacyMode(runProcess), false)
                             _           = assert(
                                             runStepNames(runProcess).contains("before-tag:plain-before-tag")
                                           )
                             _           = assert(
                                             runStepNames(runProcess)
                                               .contains("before-tag:resource-before-tag")
                                           )
                             ctx         = ReleaseContext(state = loaded.state)
                             _          <- runSteps(runProcess)
                                             .filter(_.name.startsWith("before-tag:"))
                                             .foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
                                               ioCtx
                                                 .flatTap(current => step.validate(current))
                                                 .flatMap(step.execute)
                                             }
                                             .void
                           } yield ()
                         }
          events      <- observed.get
        } yield assertEquals(
          events,
          List(
            "resource-acquire",
            "plain-execute",
            "resource-validate",
            "resource-execute",
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
        ReleaseIO.releaseIOEnableTagging := false,
        ReleaseIO.releaseIOBeforeTagHooks +=
          ReleaseHookIO.action("plain-before-tag")(_ => observed.update(_ :+ "plain-execute"))
      )

      stateResource("release-plugin-resource-disabled-phase", plugin, settings).use { loaded =>
        for {
          processMode <- resolveProcessMode(plugin, loaded.state)
          _            = assertEquals(releaseLegacyMode(processMode), false)
          _            = assert(!checkStepNames(processMode).exists(_.startsWith("before-tag:")))
          _           <- plugin.resource.use { _ =>
                           resolveReleaseRun(plugin, loaded.state, processMode).map { runProcess =>
                             assertEquals(legacyMode(runProcess), false)
                             assert(!runStepNames(runProcess).exists(_.startsWith("before-tag:")))
                           }
                         }
        } yield ()
      }
    }
  }

  test("resolveProcessMode - bypass resource-aware hooks while legacy raw process mode is active") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val plugin                    = resourceAwareHookPlugin(observed)
      val rawProcess                = Seq(ReleaseSteps.initializeVcs, ReleaseSteps.inquireVersions)
      val settings: Seq[Setting[?]] = Seq(ReleaseIO.releaseIOProcess := rawProcess)

      stateResource("release-plugin-resource-legacy-bypass", plugin, settings).use { loaded =>
        resolveProcessMode(plugin, loaded.state).flatMap { processMode =>
          for {
            _      <- IO(assertEquals(checkLegacyMode(processMode), true))
            _      <- IO(assertEquals(releaseLegacyMode(processMode), true))
            _      <- IO(assertEquals(checkStepNames(processMode), rawProcess.map(_.name)))
            _      <- IO(assert(!checkStepNames(processMode).contains("before-tag:resource-before-tag")))
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
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource("release-plugin-custom-compiled-hooks", HookFriendlyPlugin, settings).use {
      loaded =>
        resolveProcessMode(HookFriendlyPlugin, loaded.state).map { processMode =>
          assertEquals(checkLegacyMode(processMode), false)
          assertEquals(releaseLegacyMode(processMode), false)
          assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
        }
    }
  }

  test(
    "resolveProcessMode - keep an inherited custom plugin with unrelated overrides on compiled hook mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource(
      "release-plugin-inherited-compiled-hooks",
      InheritedHookFriendlyPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(InheritedHookFriendlyPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
      }
    }
  }

  test(
    "resolveProcessMode - do not evaluate hook settings while legacy raw process mode is active"
  ) {
    val rawProcess                = Seq(ReleaseSteps.initializeVcs, ReleaseSteps.inquireVersions)
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks := throwingHookSeq("hook boom")
    )

    stateResource(
      "release-plugin-legacy-throwing-hooks",
      ReleasePluginIO,
      Seq(ReleaseIO.releaseIOProcess := rawProcess)
    ).use { loaded =>
      val updatedState =
        SbtRuntime.extracted(loaded.state).appendWithSession(settings, loaded.state)

      resolveProcessMode(ReleasePluginIO, updatedState).map { processMode =>
        assertEquals(checkLegacyMode(processMode), true)
        assertEquals(releaseLegacyMode(processMode), true)
        assertEquals(checkStepNames(processMode), rawProcess.map(_.name))
      }
    }
  }

  test("resolveProcessMode - treat custom check-process wiring as legacy mode") {
    stateResource("release-plugin-legacy-check-process", CustomCheckProcessPlugin).use { loaded =>
      resolveProcessMode(CustomCheckProcessPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), true)
        assertEquals(releaseLegacyMode(processMode), false)
        assert(
          checkLegacyReasons(processMode)
            .contains("`releaseCheckProcess` differs from the configured raw process")
        )
        assert(checkStepNames(processMode).contains("custom-check-preflight"))
      }
    }
  }

  test("resolveReleaseRun - keep custom check-process wiring on compiled hook mode") {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource("release-plugin-check-process-run", CustomCheckProcessPlugin, settings).use {
      loaded =>
        for {
          processMode <- resolveProcessMode(CustomCheckProcessPlugin, loaded.state)
          runProcess  <- resolveReleaseRun(CustomCheckProcessPlugin, loaded.state, processMode)
        } yield {
          assertEquals(checkLegacyMode(processMode), true)
          assertEquals(releaseLegacyMode(processMode), false)
          assert(checkStepNames(processMode).contains("custom-check-preflight"))
          assertEquals(legacyMode(runProcess), false)
          assert(runStepNames(runProcess).contains("before-tag:before-tag-hook"))
          assert(!runStepNames(runProcess).contains("custom-check-preflight"))
        }
    }
  }

  test("resolveProcessMode - treat custom release-process wiring as legacy mode") {
    stateResource("release-plugin-legacy-release-process", CustomReleaseProcessPlugin).use {
      loaded =>
        resolveProcessMode(CustomReleaseProcessPlugin, loaded.state).map { processMode =>
          assertEquals(checkLegacyMode(processMode), false)
          assertEquals(releaseLegacyMode(processMode), true)
          assert(
            releaseLegacyReasons(processMode)
              .contains("`releaseProcess` differs from the configured raw process")
          )
          assert(releaseStepNames(processMode).contains("custom-release-step"))
        }
    }
  }

  test(
    "resolveProcessMode - treat same-length custom release-process wiring as legacy for release while keeping compiled check mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource(
      "release-plugin-same-length-check",
      SameLengthReleaseProcessPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(SameLengthReleaseProcessPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), true)
        assert(
          releaseLegacyReasons(processMode)
            .contains("`releaseProcess` differs from the configured raw process")
        )
        assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
      }
    }
  }

  test(
    "resolveReleaseRun - treat same-length custom release-process wiring as legacy on the run path"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource(
      "release-plugin-same-length-run",
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
            .contains("`releaseProcess` differs from the configured raw process")
        )
        assert(runStepNames(runProcess).contains("custom-release-replacement"))
        assert(!runStepNames(runProcess).exists(_.startsWith("before-tag:")))
      }
    }
  }

  test(
    "resolveProcessMode - treat same-name custom release-process wiring as legacy for release while keeping compiled check mode"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource(
      "release-plugin-same-name-check",
      SameNameReleaseProcessPlugin,
      settings
    ).use { loaded =>
      resolveProcessMode(SameNameReleaseProcessPlugin, loaded.state).map { processMode =>
        assertEquals(checkLegacyMode(processMode), false)
        assertEquals(releaseLegacyMode(processMode), true)
        assert(
          releaseLegacyReasons(processMode)
            .contains("`releaseProcess` differs from the configured raw process")
        )
        assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
      }
    }
  }

  test(
    "resolveReleaseRun - treat same-name custom release-process wiring as legacy on the run path"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource(
      "release-plugin-same-name-run",
      SameNameReleaseProcessPlugin,
      settings
    ).use { loaded =>
      for {
        processMode <- resolveProcessMode(SameNameReleaseProcessPlugin, loaded.state)
        runProcess  <- resolveReleaseRun(SameNameReleaseProcessPlugin, loaded.state, processMode)
      } yield {
        val rawSteps = SbtRuntime.extracted(loaded.state).get(ReleaseIO.releaseIOProcess)
        assertEquals(legacyMode(runProcess), true)
        assert(
          legacyReasons(runProcess)
            .contains("`releaseProcess` differs from the configured raw process")
        )
        assertEquals(runStepNames(runProcess).toList, rawSteps.map(_.name).toList)
        assertNotEquals(runSteps(runProcess), rawSteps)
        assert(!runStepNames(runProcess).exists(_.startsWith("before-tag:")))
      }
    }
  }

  private object HookFriendlyPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseHookFriendly"

    override def resource: Resource[IO, Unit] = Resource.unit
  }

  private abstract class BaseHookFriendlyPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseHookFriendlyInherited"

    override def resource: Resource[IO, Unit] = Resource.unit
  }

  private object InheritedHookFriendlyPlugin extends BaseHookFriendlyPlugin

  private object CustomCheckProcessPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseLegacyCheckProcess"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def releaseCheckProcess(state: State): Seq[ReleaseStepIO] =
      super.releaseCheckProcess(state) :+
        ReleaseStepIO
          .step("custom-check-preflight")
          .validateOnly
  }

  private object CustomReleaseProcessPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseLegacyReleaseProcess"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def releaseProcess(state: State): Seq[Unit => ReleaseStepIO] =
      super.releaseProcess(state) :+
        ReleaseStepIO
          .resourceStep[Unit]("custom-release-step")
          .executeAction(_ => _ => IO.unit)
  }

  private object SameLengthReleaseProcessPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseLegacySameLength"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def releaseProcess(state: State): Seq[Unit => ReleaseStepIO] =
      super.releaseProcess(state).dropRight(1) :+
        ReleaseStepIO
          .resourceStep[Unit]("custom-release-replacement")
          .executeAction(_ => _ => IO.unit)
  }

  private object SameNameReleaseProcessPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseLegacySameName"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def releaseProcess(state: State): Seq[Unit => ReleaseStepIO] =
      super.releaseProcess(state).dropRight(1) :+
        ReleaseStepIO
          .resourceStep[Unit](ReleaseSteps.defaults.last.name)
          .executeAction(_ => _ => IO.unit)
  }

  private def resourceAwareHookPlugin(observed: Ref[IO, List[String]]): ReleasePluginIOLike[Unit] =
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

  private def throwingHookSeq(message: String): Seq[ReleaseHookIO] =
    new scala.collection.immutable.Seq[ReleaseHookIO] {
      override def iterator: Iterator[ReleaseHookIO] =
        throw new RuntimeException(message)

      override def apply(idx: Int): ReleaseHookIO =
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
      plugin: ReleasePluginIOLike[Unit],
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

  private def resolveProcessMode(
      plugin: ReleasePluginIOLike[Unit],
      state: State
  ): IO[CoreCommandExecution.ResolvedProcessMode[Unit]] =
    CoreCommandExecution.resolveProcessMode(state, plugin.commandRuntime)

  private def resolveReleaseRun(
      plugin: ReleasePluginIOLike[Unit],
      state: State,
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): IO[CoreCommandExecution.ResolvedReleaseRun] =
    CoreCommandExecution.resolveReleaseRun(
      state,
      processMode,
      (),
      plugin.commandRuntime
    )

  private def logLegacyModeWarning(
      state: State,
      result: CoreCommandExecution.LegacyResult
  ): IO[Unit] =
    CoreCommandExecution.logLegacyModeWarning(state, result)

  private def legacyMode(result: CoreCommandExecution.LegacyResult): Boolean =
    result.legacyMode

  private def legacyReasons(result: CoreCommandExecution.LegacyResult): Seq[String] =
    result.legacyReasons

  private def checkLegacyMode(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Boolean =
    legacyMode(processMode.checkLegacy)

  private def checkLegacyReasons(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    legacyReasons(processMode.checkLegacy)

  private def releaseLegacyMode(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Boolean =
    legacyMode(processMode.releaseLegacy)

  private def releaseLegacyReasons(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    legacyReasons(processMode.releaseLegacy)

  private def checkStepNames(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    processMode.checkSteps.map(_.name)

  private def checkSteps(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[ReleaseStepIO] =
    processMode.checkSteps

  private def releaseStepNames(
      processMode: CoreCommandExecution.ResolvedProcessMode[Unit]
  ): Seq[String] =
    processMode.releaseSteps.map(_(()).name)

  private def runStepNames(runProcess: CoreCommandExecution.ResolvedReleaseRun): Seq[String] =
    runProcess.steps.map(_.name)

  private def runSteps(runProcess: CoreCommandExecution.ResolvedReleaseRun): Seq[ReleaseStepIO] =
    runProcess.steps
}
