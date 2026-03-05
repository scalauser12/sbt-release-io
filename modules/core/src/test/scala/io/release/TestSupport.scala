package io.release

import sbt.State
import sbt.internal.util.{AttributeMap, ConsoleOut, GlobalLogging, MainAppender}
import xsbti.*

import java.io.File

/** Shared test fixtures for constructing minimal sbt `State` and `AppConfiguration`
  * instances. Used by both core and monorepo test suites.
  */
object TestSupport {

  def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      val children = file.listFiles()
      if (children != null) children.foreach(deleteRecursively)
    }
    file.delete()
    ()
  }

  def dummyState(baseDir: File): State = {
    val logFile       = new File(baseDir, "sbt-test.log")
    val globalLogging =
      GlobalLogging.initial(
        MainAppender.globalDefault(ConsoleOut.systemOut),
        logFile,
        ConsoleOut.systemOut
      )
    State(
      configuration = dummyAppConfiguration(baseDir),
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
  }

  private class DummyAppMain extends AppMain {
    override def run(configuration: AppConfiguration): MainResult = new MainResult {}
  }

  def dummyAppConfiguration(baseDir: File): AppConfiguration = {
    val launcher: Launcher = new Launcher {
      override def getScala(version: String): ScalaProvider                                   = ???
      override def getScala(version: String, reason: String): ScalaProvider                   = ???
      override def getScala(version: String, reason: String, scalaOrg: String): ScalaProvider = ???
      override def app(id: ApplicationID, version: String): AppProvider                       = ???
      override def topLoader(): ClassLoader                                                   = getClass.getClassLoader
      override def globalLock(): GlobalLock                                                   = new GlobalLock {
        override def apply[T](lockFile: File, run: java.util.concurrent.Callable[T]): T = run.call()
      }
      override def bootDirectory(): File                                                      = baseDir
      override def ivyRepositories(): Array[Repository]                                       = Array.empty
      override def appRepositories(): Array[Repository]                                       = Array.empty
      override def isOverrideRepositories(): Boolean                                          = false
      override def ivyHome(): File                                                            = baseDir
      override def checksums(): Array[String]                                                 = Array.empty
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

    lazy val scalaProvider: ScalaProvider = new ScalaProvider {
      override def launcher(): Launcher                = launcher
      override def version(): String                   = "2.12.21"
      override def loader(): ClassLoader               = getClass.getClassLoader
      override def jars(): Array[File]                 = Array.empty
      override def libraryJar(): File                  = new File(baseDir, "scala-library.jar")
      override def compilerJar(): File                 = new File(baseDir, "scala-compiler.jar")
      override def app(id: ApplicationID): AppProvider = appProvider
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
