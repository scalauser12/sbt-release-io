package io.release.monorepo

import cats.effect.IO
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
import java.lang.reflect.Method
import scala.annotation.nowarn
import scala.annotation.tailrec

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
        _            = assertEquals(legacyMode(processMode), true)
        _            = assert(
                         legacyReasons(processMode)
                           .contains("`releaseIOMonorepoProcess` differs from defaults")
                       )
        _            = assertEquals(checkStepNames(processMode), rawProcess.map(_.name))
        _            = assert(
                         !checkStepNames(processMode).exists(_.startsWith("before-selection:"))
                       )
        _           <- logLegacyModeWarning(
                         MonorepoReleasePlugin,
                         loaded.state,
                         legacyMode(processMode),
                         legacyReasons(processMode)
                       )
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
        assertEquals(legacyMode(processMode), false)
        assert(checkStepNames(processMode).exists(_ == "before-selection:before-selection-hook"))
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
          assertEquals(legacyMode(processMode), false)
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
        assertEquals(legacyMode(processMode), false)
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
        assertEquals(legacyMode(processMode), true)
        assertEquals(checkStepNames(processMode), rawProcess.map(_.name))
      }
    }
  }

  test("resolveProcessMode - treat custom check-process wiring as legacy mode") {
    stateResource("monorepo-plugin-legacy-check-process", CustomCheckProcessPlugin).use { loaded =>
      resolveProcessMode(CustomCheckProcessPlugin, loaded.state).map { processMode =>
        assertEquals(legacyMode(processMode), true)
        assert(
          legacyReasons(processMode)
            .contains(
              "`monorepoReleaseCheckProcess` differs from the configured raw process"
            )
        )
        assert(checkStepNames(processMode).contains("custom-check-preflight"))
      }
    }
  }

  test("resolveProcessMode - treat custom release-process wiring as legacy mode") {
    stateResource("monorepo-plugin-legacy-release-process", CustomReleaseProcessPlugin).use {
      loaded =>
        resolveProcessMode(CustomReleaseProcessPlugin, loaded.state).map { processMode =>
          assertEquals(legacyMode(processMode), true)
          assert(
            legacyReasons(processMode)
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
        assertEquals(legacyMode(processMode), false)
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
        assertEquals(legacyMode(processMode), false)
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
  ): IO[AnyRef] =
    invokeHiddenIO[AnyRef](plugin, "resolveProcessMode", state)

  private def resolveReleaseRun(
      plugin: MonorepoReleasePluginLike[Unit],
      state: State,
      processMode: AnyRef
  ): IO[AnyRef] =
    invokeHiddenIO[AnyRef](
      plugin,
      "resolveReleaseRun",
      state,
      processMode,
      scala.runtime.BoxedUnit.UNIT
    )

  private def logLegacyModeWarning(
      plugin: MonorepoReleasePluginLike[Unit],
      state: State,
      legacyMode: Boolean,
      legacyReasons: Seq[String]
  ): IO[Unit] =
    invokeHiddenIO[Unit](
      plugin,
      "logLegacyModeWarning",
      state,
      Boolean.box(legacyMode),
      legacyReasons.asInstanceOf[AnyRef]
    )

  private def legacyMode(processMode: AnyRef): Boolean =
    invokeGetter[Boolean](processMode, "legacyMode")

  private def legacyReasons(processMode: AnyRef): Seq[String] =
    invokeGetter[Seq[String]](processMode, "legacyReasons")

  private def checkStepNames(processMode: AnyRef): Seq[String] =
    invokeGetter[Seq[MonorepoStepIO]](processMode, "checkSteps").map(_.name)

  private def releaseStepNames(processMode: AnyRef): Seq[String] =
    invokeGetter[Seq[Unit => MonorepoStepIO]](processMode, "releaseSteps").map(_(()).name)

  private def runStepNames(processMode: AnyRef): Seq[String] =
    invokeGetter[Seq[MonorepoStepIO]](processMode, "steps").map(_.name)

  private def runSteps(processMode: AnyRef): Seq[MonorepoStepIO] =
    invokeGetter[Seq[MonorepoStepIO]](processMode, "steps")

  private def invokeGetter[A](target: AnyRef, methodName: String): A = {
    val method = findMethod(target.getClass, _.endsWith(methodName), 0)
    method.invoke(target).asInstanceOf[A]
  }

  private def invokeHiddenIO[A](target: AnyRef, nameFragment: String, args: AnyRef*): IO[A] = {
    val method = findMethod(target.getClass, _.endsWith(nameFragment), args.length)
    method.invoke(target, args*).asInstanceOf[IO[A]]
  }

  private def findMethod(
      clazz: Class[?],
      nameMatches: String => Boolean,
      arity: Int
  ): Method = {
    @tailrec
    def loop(pending: List[Class[?]], seen: Set[Class[?]]): Option[Method] =
      pending match {
        case Nil                        => None
        case head :: tail if seen(head) =>
          loop(tail, seen)
        case head :: tail               =>
          head.getDeclaredMethods.find(method =>
            nameMatches(method.getName) && method.getParameterCount == arity
          ) match {
            case some @ Some(method) =>
              method.setAccessible(true)
              some
            case None                =>
              loop(
                head.getInterfaces.toList ++ Option(head.getSuperclass).toList ++ tail,
                seen + head
              )
          }
      }

    loop(List(clazz), Set.empty).getOrElse {
      fail(s"Expected method with arity $arity on ${clazz.getName}")
    }
  }
}
