package io.release

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.specs2.mutable.Specification
import sbt.State
import sbt.internal.util.{AttributeMap, ConsoleOut, GlobalLogging, MainAppender}
import sbtrelease.Compat
import xsbti.*

import java.io.File
import java.nio.file.Files
import scala.collection.mutable.ArrayBuffer

class ReleaseStepIOSpec extends Specification {

  "ReleaseStepIO.compose" should {
    "run checks before actions and fail fast on check error" in withContext { ctx =>
      val observed = ArrayBuffer.empty[String]

      val step1 = ReleaseStepIO(
        name = "step1",
        action = c => IO { observed += "action1"; c },
        check = c => IO { observed += "check1"; c }
      )

      val step2 = ReleaseStepIO(
        name = "step2",
        action = c => IO { observed += "action2"; c },
        check = _ =>
          IO {
            observed += "check2"
            throw new RuntimeException("check failed")
          }
      )

      ReleaseStepIO.compose(Seq(step1, step2), crossBuild = false)(ctx).unsafeRunSync() must
        throwA[RuntimeException](message = "check failed")

      observed.toList must_== List("check1", "check2")
    }

    "mark the release as failed when an action throws and skip remaining actions" in withContext {
      ctx =>
        val observed = ArrayBuffer.empty[String]

        val failing = ReleaseStepIO.io("failing") { c =>
          IO {
            observed += "action1"
            throw new RuntimeException("boom")
          }
        }

        val skipped = ReleaseStepIO.io("skipped") { c =>
          IO {
            observed += "action2"
            c
          }
        }

        ReleaseStepIO
          .compose(Seq(failing, skipped), crossBuild = false)(ctx)
          .unsafeRunSync() must throwA[RuntimeException](message = "Release process failed")

        observed.toList must_== List("action1")
    }

    "detect FailureCommand sentinel and skip subsequent actions" in withContext { ctx =>
      val observed = ArrayBuffer.empty[String]

      val injectFailure = ReleaseStepIO.io("inject-failure-command") { c =>
        IO {
          observed += "action1"
          c.copy(state = c.state.copy(remainingCommands = Compat.FailureCommand :: Nil))
        }
      }

      val skipped = ReleaseStepIO.io("skipped") { c =>
        IO {
          observed += "action2"
          c
        }
      }

      ReleaseStepIO
        .compose(Seq(injectFailure, skipped), crossBuild = false)(ctx)
        .unsafeRunSync() must throwA[RuntimeException](message = "Release process failed")

      observed.toList must_== List("action1")
    }
  }

  "ReleaseStepIO command steps" should {
    "surface command parse failures for fromCommand" in withContext { ctx =>
      val step = ReleaseStepIO.fromCommand("this-command-does-not-exist")

      step.action(ctx).unsafeRunSync() must throwA[RuntimeException].like {
        case e =>
          e.getMessage must contain("Failed to parse command")
      }
    }

    "surface command parse failures for fromCommandAndRemaining" in withContext { ctx =>
      val step = ReleaseStepIO.fromCommandAndRemaining("this-command-does-not-exist")

      step.action(ctx).unsafeRunSync() must throwA[RuntimeException].like {
        case e =>
          e.getMessage must contain("Failed to parse command")
      }
    }
  }

  private def withContext[A](f: ReleaseContext => A): A = withTempDir { dir =>
    val logFile = new File(dir, "sbt-test.log")
    val globalLogging =
      GlobalLogging.initial(
        MainAppender.globalDefault(ConsoleOut.systemOut),
        logFile,
        ConsoleOut.systemOut
      )

    val state = State(
      configuration = dummyAppConfiguration(dir),
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

    f(ReleaseContext(state = state))
  }

  private def withTempDir[A](f: File => A): A = {
    val dir = Files.createTempDirectory("sbt-release-io-compose-spec").toFile
    try f(dir)
    finally deleteRecursively(dir)
  }

  private def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      val children = file.listFiles()
      if (children != null) children.foreach(deleteRecursively)
    }
    file.delete()
    ()
  }

  private def dummyAppConfiguration(baseDir: File): AppConfiguration = {
    val launcher: Launcher = new Launcher {
      override def getScala(version: String): ScalaProvider = null
      override def getScala(version: String, reason: String): ScalaProvider = null
      override def getScala(version: String, reason: String, scalaOrg: String): ScalaProvider = null
      override def app(id: ApplicationID, version: String): AppProvider = null
      override def topLoader(): ClassLoader = getClass.getClassLoader
      override def globalLock(): GlobalLock = new GlobalLock {
        override def apply[T](lockFile: File, run: java.util.concurrent.Callable[T]): T = run.call()
      }
      override def bootDirectory(): File = baseDir
      override def ivyRepositories(): Array[Repository] = Array.empty
      override def appRepositories(): Array[Repository] = Array.empty
      override def isOverrideRepositories(): Boolean = false
      override def ivyHome(): File = baseDir
      override def checksums(): Array[String] = Array.empty
    }

    val appId: ApplicationID = new ApplicationID {
      override def groupID(): String = "test"
      override def name(): String = "test"
      override def version(): String = "0.0.0"
      override def mainClass(): String = "test.Main"
      override def mainComponents(): Array[String] = Array.empty
      override def crossVersioned(): Boolean = false
      override def crossVersionedValue(): CrossValue = CrossValue.Disabled
      override def classpathExtra(): Array[File] = Array.empty
    }

    class DummyAppMain extends AppMain {
      override def run(configuration: AppConfiguration): MainResult = new MainResult {}
    }

    lazy val scalaProvider: ScalaProvider = new ScalaProvider {
      override def launcher(): Launcher = launcher
      override def version(): String = "2.12.21"
      override def loader(): ClassLoader = getClass.getClassLoader
      override def jars(): Array[File] = Array.empty
      override def libraryJar(): File = new File(baseDir, "scala-library.jar")
      override def compilerJar(): File = new File(baseDir, "scala-compiler.jar")
      override def app(id: ApplicationID): AppProvider = appProvider
    }

    lazy val componentProvider: ComponentProvider = new ComponentProvider {
      override def componentLocation(id: String): File = new File(baseDir, s"component-$id")
      override def component(id: String): Array[File] = Array.empty
      override def defineComponent(id: String, files: Array[File]): Unit = ()
      override def addToComponent(id: String, files: Array[File]): Boolean = true
      override def lockFile(): File = new File(baseDir, ".components.lock")
    }

    lazy val appProvider: AppProvider = new AppProvider {
      override def scalaProvider(): ScalaProvider = scalaProvider
      override def id(): ApplicationID = appId
      override def loader(): ClassLoader = getClass.getClassLoader
      override def mainClass(): Class[_ <: AppMain] = classOf[DummyAppMain]
      override def entryPoint(): Class[_] = classOf[DummyAppMain]
      override def newMain(): AppMain = new DummyAppMain
      override def mainClasspath(): Array[File] = Array.empty
      override def components(): ComponentProvider = componentProvider
    }

    new AppConfiguration {
      override def arguments(): Array[String] = Array.empty
      override def baseDirectory(): File = baseDir
      override def provider(): AppProvider = appProvider
    }
  }
}
