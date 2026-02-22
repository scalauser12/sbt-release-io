package io.release.monorepo

import org.specs2.mutable.Specification
import sbt.{State, ProjectRef}
import sbt.internal.util.{AttributeMap, ConsoleOut, GlobalLogging, MainAppender}
import xsbti.*

import java.io.File
import java.nio.file.Files

class MonorepoContextSpec extends Specification {

  "MonorepoContext" should {

    "update a specific project" in withState { state =>
      val projects = Seq(dummyProject("core"), dummyProject("api"))
      val ctx      = MonorepoContext(state = state, projects = projects)
      val updated  =
        ctx.updateProject(projects(0).ref)(_.copy(versions = Some(("1.0.0", "1.1.0-SNAPSHOT"))))

      updated.projects(0).versions must beSome(("1.0.0", "1.1.0-SNAPSHOT"))
      updated.projects(1).versions must beNone
    }

    "filter out failed projects in currentProjects" in withState { state =>
      val projects = Seq(
        dummyProject("core").copy(failed = true),
        dummyProject("api")
      )
      val ctx = MonorepoContext(state = state, projects = projects)

      ctx.currentProjects.map(_.name) must_== Seq("api")
    }

    "manage attributes" in withState { state =>
      val ctx     = MonorepoContext(state = state)
      val updated = ctx.withAttr("key1", "val1").withAttr("key2", "val2")

      updated.attr("key1") must beSome("val1")
      updated.attr("key2") must beSome("val2")
      updated.attr("missing") must beNone
    }

    "mark as failed" in withState { state =>
      val ctx = MonorepoContext(state = state)
      ctx.failed must_== false
      ctx.fail.failed must_== true
    }

    "convert to ReleaseContext preserving shared state" in withState { state =>
      val ctx = MonorepoContext(
        state = state,
        skipTests = true,
        skipPublish = true,
        interactive = false
      )
      val rc = ctx.toReleaseContext

      rc.skipTests must_== true
      rc.skipPublish must_== true
      rc.interactive must_== false
      rc.versions must beNone
    }

    "replace projects via withProjects" in withState { state =>
      val ctx     = MonorepoContext(state = state, projects = Seq(dummyProject("old")))
      val updated = ctx.withProjects(Seq(dummyProject("new1"), dummyProject("new2")))

      updated.projects.map(_.name) must_== Seq("new1", "new2")
    }
  }

  "ProjectReleaseInfo" should {

    "have sensible defaults" in {
      val proj = dummyProject("test")
      proj.versions must beNone
      proj.tagName must beNone
      proj.released must_== false
      proj.failed must_== false
    }
  }

  "MonorepoTagStrategy" should {

    "have PerProject and Unified variants" in {
      val pp: MonorepoTagStrategy = MonorepoTagStrategy.PerProject
      val u: MonorepoTagStrategy  = MonorepoTagStrategy.Unified
      pp must not(equalTo(u))
    }
  }

  private def dummyProject(name: String): ProjectReleaseInfo =
    ProjectReleaseInfo(
      ref = ProjectRef(new java.net.URI("file:///tmp/test"), name),
      name = name,
      baseDir = new java.io.File(s"/tmp/test/$name"),
      versionFile = new java.io.File(s"/tmp/test/$name/version.sbt")
    )

  private def withState[A](f: State => A): A = {
    val dir     = Files.createTempDirectory("monorepo-ctx-spec").toFile
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
      f(state)
    } finally {
      if (dir.isDirectory) {
        val children = dir.listFiles()
        if (children != null) children.foreach(_.delete())
      }
      dir.delete()
      ()
    }
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
      override def groupID(): String                 = "test"
      override def name(): String                    = "test"
      override def version(): String                 = "0.0.0"
      override def mainClass(): String               = "test.Main"
      override def mainComponents(): Array[String]   = Array.empty
      override def crossVersioned(): Boolean         = false
      override def crossVersionedValue(): CrossValue = CrossValue.Disabled
      override def classpathExtra(): Array[File]     = Array.empty
    }

    class DummyAppMain extends AppMain {
      override def run(configuration: AppConfiguration): MainResult = new MainResult {}
    }

    lazy val scalaProvider: ScalaProvider = new ScalaProvider {
      override def launcher(): Launcher                        = launcher
      override def version(): String                           = "2.12.21"
      override def loader(): ClassLoader                       = getClass.getClassLoader
      override def jars(): Array[File]                         = Array.empty
      override def libraryJar(): File                          = new File(baseDir, "scala-library.jar")
      override def compilerJar(): File                         = new File(baseDir, "scala-compiler.jar")
      override def app(id: ApplicationID): AppProvider         = appProvider
    }

    lazy val componentProvider: ComponentProvider = new ComponentProvider {
      override def componentLocation(id: String): File                     = new File(baseDir, s"component-$id")
      override def component(id: String): Array[File]                      = Array.empty
      override def defineComponent(id: String, files: Array[File]): Unit   = ()
      override def addToComponent(id: String, files: Array[File]): Boolean = true
      override def lockFile(): File                                        = new File(baseDir, ".components.lock")
    }

    lazy val appProvider: AppProvider = new AppProvider {
      override def scalaProvider(): ScalaProvider   = scalaProvider
      override def id(): ApplicationID              = appId
      override def loader(): ClassLoader            = getClass.getClassLoader
      override def mainClass(): Class[_ <: AppMain] = classOf[DummyAppMain]
      override def entryPoint(): Class[_]           = classOf[DummyAppMain]
      override def newMain(): AppMain               = new DummyAppMain
      override def mainClasspath(): Array[File]     = Array.empty
      override def components(): ComponentProvider  = componentProvider
    }

    new AppConfiguration {
      override def arguments(): Array[String] = Array.empty
      override def baseDirectory(): File      = baseDir
      override def provider(): AppProvider    = appProvider
    }
  }
}
