package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.TestSupport
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
        _           <- logLegacyModeWarning(MonorepoReleasePlugin, loaded.state, processMode)
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
    stateResource("monorepo-plugin-legacy-override", OverriddenProcessPlugin).use { loaded =>
      resolveProcessMode(OverriddenProcessPlugin, loaded.state).map { processMode =>
        assertEquals(legacyMode(processMode), true)
        assert(
          legacyReasons(processMode)
            .contains(
              "plugin overrides `monorepoReleaseProcess` or `monorepoReleaseCheckProcess`"
            )
        )
      }
    }
  }

  private object OverriddenProcessPlugin extends MonorepoReleasePluginLike[Unit] {
    override def trigger = noTrigger

    override protected def commandName = "releaseMonorepoLegacyOverride"

    override def resource: Resource[IO, Unit] = Resource.unit

    override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
      super.monorepoReleaseProcess(state)

    override protected def monorepoReleaseCheckProcess(state: State): Seq[MonorepoStepIO] =
      super.monorepoReleaseCheckProcess(state)
  }

  private final case class LoadedState(
      dir: File,
      state: State,
      consoleBuffer: ByteArrayOutputStream
  )

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
      MonorepoReleaseIO.releaseIOMonorepoEnableRunTests                  := true,
      MonorepoReleaseIO.releaseIOMonorepoEnableTagging                   := true,
      MonorepoReleaseIO.releaseIOMonorepoEnablePublish                   := true,
      MonorepoReleaseIO.releaseIOMonorepoEnablePush                      := true,
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

  private def logLegacyModeWarning(
      plugin: MonorepoReleasePluginLike[Unit],
      state: State,
      processMode: AnyRef
  ): IO[Unit] =
    invokeHiddenIO[Unit](plugin, "logLegacyModeWarning", state, processMode)

  private def legacyMode(processMode: AnyRef): Boolean =
    invokeGetter[Boolean](processMode, "legacyMode")

  private def legacyReasons(processMode: AnyRef): Seq[String] =
    invokeGetter[Seq[String]](processMode, "legacyReasons")

  private def checkStepNames(processMode: AnyRef): Seq[String] =
    invokeGetter[Seq[MonorepoStepIO]](processMode, "checkSteps").map(_.name)

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
