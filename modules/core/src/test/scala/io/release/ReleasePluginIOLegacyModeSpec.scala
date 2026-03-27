package io.release

import cats.effect.IO
import cats.effect.Resource
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
        _           <- logLegacyModeWarning(ReleasePluginIO, loaded.state, processMode)
        log         <- IO.blocking(loaded.consoleBuffer.toString("UTF-8"))
      } yield {
        assert(log.contains("Legacy raw process mode enabled"))
        assert(
          log.contains("Hook/policy settings are ignored while legacy raw process mode is active.")
        )
      }
    }
  }

  test("resolveProcessMode - treat process hook overrides as legacy mode") {
    stateResource("release-plugin-legacy-override", OverriddenProcessPlugin).use { loaded =>
      resolveProcessMode(OverriddenProcessPlugin, loaded.state).map { processMode =>
        assertEquals(legacyMode(processMode), true)
        assert(
          legacyReasons(processMode)
            .contains("plugin overrides `releaseProcess` or `releaseCheckProcess`")
        )
      }
    }
  }

  private object OverriddenProcessPlugin extends ReleasePluginIOLike[Unit] {
    override def trigger = noTrigger

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def releaseProcess(state: State): Seq[Unit => ReleaseStepIO] =
      super.releaseProcess(state)

    override protected def releaseCheckProcess(state: State): Seq[ReleaseStepIO] =
      super.releaseCheckProcess(state)
  }

  private final case class LoadedState(
      dir: File,
      state: State,
      consoleBuffer: ByteArrayOutputStream
  )

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

  private def logLegacyModeWarning(
      plugin: ReleasePluginIOLike[Unit],
      state: State,
      processMode: AnyRef
  ): IO[Unit] =
    invokeHiddenIO[Unit](plugin, "logLegacyModeWarning", state, processMode)

  private def legacyMode(processMode: AnyRef): Boolean =
    invokeGetter[Boolean](processMode, "legacyMode")

  private def legacyReasons(processMode: AnyRef): Seq[String] =
    invokeGetter[Seq[String]](processMode, "legacyReasons")

  private def checkStepNames(processMode: AnyRef): Seq[String] =
    invokeGetter[Seq[ReleaseStepIO]](processMode, "checkSteps").map(_.name)

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
