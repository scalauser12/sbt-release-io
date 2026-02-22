package io.release.monorepo

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.specs2.mutable.Specification
import sbt.{State, ProjectRef}
import sbt.internal.util.{AttributeMap, ConsoleOut, GlobalLogging, MainAppender}
import xsbti.*

import java.io.File
import java.nio.file.Files
import scala.collection.mutable.ArrayBuffer

class MonorepoStepIOSpec extends Specification {

  "MonorepoStepIO.compose" should {

    "run global checks before actions" in withContext { ctx =>
      val log = ArrayBuffer.empty[String]

      val step = MonorepoStepIO.Global(
        name = "test-step",
        check = c => IO(log += "check").as(c),
        action = c => IO(log += "action").as(c)
      )

      MonorepoStepIO.compose(Seq(step))(ctx).unsafeRunSync()
      log.toList must_== List("check", "action")
    }

    "abort on check failure without running actions" in withContext { ctx =>
      val log = ArrayBuffer.empty[String]

      val step = MonorepoStepIO.Global(
        name = "failing-check",
        check = _ => IO.raiseError(new RuntimeException("check failed")),
        action = c => IO(log += "action").as(c)
      )

      MonorepoStepIO.compose(Seq(step))(ctx).unsafeRunSync() must
        throwA[RuntimeException]("check failed")
      log.toList must_== List()
    }

    "iterate PerProject steps over all projects" in withContext { ctx =>
      val log      = ArrayBuffer.empty[String]
      val projects = Seq(dummyProject("core"), dummyProject("api"))
      val pCtx     = ctx.withProjects(projects)

      val step = MonorepoStepIO.PerProject(
        name = "per-project-step",
        action = (c, proj) => IO(log += proj.name).as(c)
      )

      MonorepoStepIO.compose(Seq(step))(pCtx).unsafeRunSync()
      log.toList must_== List("core", "api")
    }

    "skip failed projects in subsequent PerProject steps" in withContext { ctx =>
      val log      = ArrayBuffer.empty[String]
      val projects = Seq(dummyProject("core"), dummyProject("api"))
      val pCtx     = ctx.withProjects(projects)

      val failingStep = MonorepoStepIO.PerProject(
        name = "failing-step",
        action = (c, proj) =>
          if (proj.name == "core") IO.raiseError(new RuntimeException("core failed"))
          else IO(log += s"step1:${proj.name}").as(c)
      )

      val secondStep = MonorepoStepIO.PerProject(
        name = "second-step",
        action = (c, proj) => IO(log += s"step2:${proj.name}").as(c)
      )

      val result =
        MonorepoStepIO.compose(Seq(failingStep, secondStep))(pCtx).unsafeRunSync()
      log.toList must_== List("step1:api", "step2:api")
      result.projects.find(_.name == "core").get.failed must_== true
    }

    "thread MonorepoContext through sequential steps" in withContext { ctx =>
      val step1 = MonorepoStepIO.Global(
        name = "set-attr",
        action = c => IO.pure(c.withAttr("key", "value"))
      )
      val step2 = MonorepoStepIO.Global(
        name = "read-attr",
        action = c => {
          if (c.attr("key").contains("value")) IO.pure(c.withAttr("verified", "true"))
          else IO.raiseError(new RuntimeException("attribute not threaded"))
        }
      )

      val result = MonorepoStepIO.compose(Seq(step1, step2))(ctx).unsafeRunSync()
      result.attr("verified") must beSome("true")
    }

    "mark entire release as failed when global action fails" in withContext { ctx =>
      val step = MonorepoStepIO.Global(
        name = "global-fail",
        action = _ => IO.raiseError(new RuntimeException("global failure"))
      )

      MonorepoStepIO
        .compose(Seq(step))(ctx)
        .unsafeRunSync() must throwA[RuntimeException]("Monorepo release process failed")
    }

    "run PerProject checks for all projects during check phase" in withContext { ctx =>
      val log      = ArrayBuffer.empty[String]
      val projects = Seq(dummyProject("core"), dummyProject("api"))
      val pCtx     = ctx.withProjects(projects)

      val step = MonorepoStepIO.PerProject(
        name = "checked-step",
        check = (c, proj) => IO(log += s"check:${proj.name}").as(c),
        action = (c, proj) => IO(log += s"action:${proj.name}").as(c)
      )

      MonorepoStepIO.compose(Seq(step))(pCtx).unsafeRunSync()
      log.toList must_== List("check:core", "check:api", "action:core", "action:api")
    }
  }

  private def dummyProject(name: String): ProjectReleaseInfo =
    ProjectReleaseInfo(
      ref = ProjectRef(new java.net.URI("file:///tmp/test"), name),
      name = name,
      baseDir = new java.io.File(s"/tmp/test/$name"),
      versionFile = new java.io.File(s"/tmp/test/$name/version.sbt")
    )

  private def withContext[A](f: MonorepoContext => A): A = {
    val dir     = Files.createTempDirectory("monorepo-step-spec").toFile
    val logFile = new File(dir, "sbt-test.log")
    try {
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

      f(MonorepoContext(state = state))
    } finally deleteRecursively(dir)
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
      override def getScala(version: String): ScalaProvider                                 = null
      override def getScala(version: String, reason: String): ScalaProvider                 = null
      override def getScala(version: String, reason: String, scalaOrg: String): ScalaProvider = null
      override def app(id: ApplicationID, version: String): AppProvider                     = null
      override def topLoader(): ClassLoader                                                 = getClass.getClassLoader
      override def globalLock(): GlobalLock = new GlobalLock {
        override def apply[T](lockFile: File, run: java.util.concurrent.Callable[T]): T = run.call()
      }
      override def bootDirectory(): File                = baseDir
      override def ivyRepositories(): Array[Repository] = Array.empty
      override def appRepositories(): Array[Repository] = Array.empty
      override def isOverrideRepositories(): Boolean     = false
      override def ivyHome(): File                       = baseDir
      override def checksums(): Array[String]            = Array.empty
    }

    val appId: ApplicationID = new ApplicationID {
      override def groupID(): String                    = "test"
      override def name(): String                       = "test"
      override def version(): String                    = "0.0.0"
      override def mainClass(): String                  = "test.Main"
      override def mainComponents(): Array[String]      = Array.empty
      override def crossVersioned(): Boolean            = false
      override def crossVersionedValue(): CrossValue    = CrossValue.Disabled
      override def classpathExtra(): Array[File]        = Array.empty
    }

    class DummyAppMain extends AppMain {
      override def run(configuration: AppConfiguration): MainResult = new MainResult {}
    }

    lazy val scalaProvider: ScalaProvider = new ScalaProvider {
      override def launcher(): Launcher        = launcher
      override def version(): String           = "2.12.21"
      override def loader(): ClassLoader       = getClass.getClassLoader
      override def jars(): Array[File]         = Array.empty
      override def libraryJar(): File          = new File(baseDir, "scala-library.jar")
      override def compilerJar(): File         = new File(baseDir, "scala-compiler.jar")
      override def app(id: ApplicationID): AppProvider = appProvider
    }

    lazy val componentProvider: ComponentProvider = new ComponentProvider {
      override def componentLocation(id: String): File              = new File(baseDir, s"component-$id")
      override def component(id: String): Array[File]               = Array.empty
      override def defineComponent(id: String, files: Array[File]): Unit = ()
      override def addToComponent(id: String, files: Array[File]): Boolean = true
      override def lockFile(): File                                 = new File(baseDir, ".components.lock")
    }

    lazy val appProvider: AppProvider = new AppProvider {
      override def scalaProvider(): ScalaProvider              = scalaProvider
      override def id(): ApplicationID                         = appId
      override def loader(): ClassLoader                       = getClass.getClassLoader
      override def mainClass(): Class[_ <: AppMain]            = classOf[DummyAppMain]
      override def entryPoint(): Class[_]                      = classOf[DummyAppMain]
      override def newMain(): AppMain                          = new DummyAppMain
      override def mainClasspath(): Array[File]                = Array.empty
      override def components(): ComponentProvider             = componentProvider
    }

    new AppConfiguration {
      override def arguments(): Array[String] = Array.empty
      override def baseDirectory(): File      = baseDir
      override def provider(): AppProvider    = appProvider
    }
  }
}
