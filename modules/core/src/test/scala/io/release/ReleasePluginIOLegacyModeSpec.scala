package io.release

import cats.effect.IO
import cats.effect.Resource
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
import java.lang.reflect.Method
import scala.annotation.nowarn
import scala.annotation.tailrec

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
        _            = assertEquals(legacyMode(processMode), true)
        _            = assert(
                         legacyReasons(processMode)
                           .contains("`releaseIOProcess` differs from defaults")
                       )
        _            = assertEquals(checkStepNames(processMode), rawProcess.map(_.name))
        _            = assert(!checkStepNames(processMode).exists(_.startsWith("before-tag:")))
        _           <- logLegacyModeWarning(
                         ReleasePluginIO,
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
      ReleaseIO.releaseIOBeforeTagHooks += ReleaseHookIO.action("before-tag-hook")(_ => IO.unit)
    )

    stateResource("release-plugin-compiled-hooks", ReleasePluginIO, settings).use { loaded =>
      resolveProcessMode(ReleasePluginIO, loaded.state).map { processMode =>
        assertEquals(legacyMode(processMode), false)
        assert(checkStepNames(processMode).exists(_ == "before-tag:before-tag-hook"))
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
          assertEquals(legacyMode(processMode), false)
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
        assertEquals(legacyMode(processMode), false)
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
        assertEquals(legacyMode(processMode), true)
        assertEquals(checkStepNames(processMode), rawProcess.map(_.name))
      }
    }
  }

  test("resolveProcessMode - treat custom check-process wiring as legacy mode") {
    stateResource("release-plugin-legacy-check-process", CustomCheckProcessPlugin).use { loaded =>
      resolveProcessMode(CustomCheckProcessPlugin, loaded.state).map { processMode =>
        assertEquals(legacyMode(processMode), true)
        assert(
          legacyReasons(processMode)
            .contains("`releaseCheckProcess` differs from the configured raw process")
        )
        assert(checkStepNames(processMode).contains("custom-check-preflight"))
      }
    }
  }

  test("resolveProcessMode - treat custom release-process wiring as legacy mode") {
    stateResource("release-plugin-legacy-release-process", CustomReleaseProcessPlugin).use {
      loaded =>
        resolveProcessMode(CustomReleaseProcessPlugin, loaded.state).map { processMode =>
          assertEquals(legacyMode(processMode), true)
          assert(
            legacyReasons(processMode)
              .contains("`releaseProcess` differs from the configured raw process")
          )
          assert(releaseStepNames(processMode).contains("custom-release-step"))
        }
    }
  }

  test(
    "resolveProcessMode - keep same-length custom release-process wiring on compiled check mode"
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
        assertEquals(legacyMode(processMode), false)
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
    "resolveProcessMode - keep same-name custom release-process wiring on compiled check mode"
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
        assertEquals(legacyMode(processMode), false)
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
  ): IO[AnyRef] =
    invokeHiddenIO[AnyRef](plugin, "resolveProcessMode", state)

  private def resolveReleaseRun(
      plugin: ReleasePluginIOLike[Unit],
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
      plugin: ReleasePluginIOLike[Unit],
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
    invokeGetter[Seq[ReleaseStepIO]](processMode, "checkSteps").map(_.name)

  private def releaseStepNames(processMode: AnyRef): Seq[String] =
    invokeGetter[Seq[Unit => ReleaseStepIO]](processMode, "releaseSteps").map(_(()).name)

  private def runStepNames(processMode: AnyRef): Seq[String] =
    invokeGetter[Seq[ReleaseStepIO]](processMode, "steps").map(_.name)

  private def runSteps(processMode: AnyRef): Seq[ReleaseStepIO] =
    invokeGetter[Seq[ReleaseStepIO]](processMode, "steps")

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
