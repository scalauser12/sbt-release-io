package io.release.monorepo

import cats.effect.unsafe.implicits.global
import org.specs2.mutable.Specification
import sbt.*
import sbt.internal.util.{AttributeMap, ConsoleOut, GlobalLogging, MainAppender}
import sbtrelease.Vcs
import xsbti.*

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import scala.sys.process.Process

class ChangeDetectionSpec extends Specification {

  "ChangeDetection.detectChangedProjects" should {

    "mark project as changed and log warning when git describe fails unexpectedly" in withTempDirectory {
      repo =>
        IO.createDirectory(repo / "core")
        IO.write(repo / "core/version.sbt", """version := "0.1.0-SNAPSHOT"""" + "\n")

        initGitRepo(repo)
        runGit(repo, "add", ".")
        runGit(repo, "commit", "-m", "Initial commit")
        runGit(repo, "tag", "core-v0.1.0")

        val vcs = Vcs.detect(repo).getOrElse(
          throw new RuntimeException(s"Failed to detect VCS in ${repo.getAbsolutePath}")
        )

        IO.move(repo / ".git", repo / ".git-broken")

        val logFile = repo / "sbt-test.log"
        val env     = testEnv(repo, logFile)
        val project = ProjectReleaseInfo(
          ref = ProjectRef(repo.toURI, "core"),
          name = "core",
          baseDir = repo / "core",
          versionFile = repo / "core/version.sbt"
        )

        val changed = detectChanged(vcs, Seq(project), env.state)
        env.state.log.flush()
        val logs    = env.consoleBuffer.toString("UTF-8")
        changed.map(_.name) must_== Seq("core")
        logs must contain("git describe failed for core")
        logs must not(contain("No previous tag matching"))
    }

    "mark project as changed when project baseDir is outside VCS baseDir" in withTempDirectory {
      repo =>
        IO.createDirectory(repo / "core")
        IO.write(repo / "core/version.sbt", """version := "0.1.0-SNAPSHOT"""" + "\n")

        initGitRepo(repo)
        runGit(repo, "add", ".")
        runGit(repo, "commit", "-m", "Initial commit")
        runGit(repo, "tag", "core-v0.1.0")

        val vcs = Vcs.detect(repo).getOrElse(
          throw new RuntimeException(s"Failed to detect VCS in ${repo.getAbsolutePath}")
        )

        val outsideBaseDir = Files.createTempDirectory("change-detection-outside").toFile
        try {
          val logFile = repo / "sbt-test.log"
          val env     = testEnv(repo, logFile)
          val project = ProjectReleaseInfo(
            ref = ProjectRef(repo.toURI, "core"),
            name = "core",
            baseDir = outsideBaseDir,
            versionFile = repo / "core/version.sbt"
          )

          val changed = detectChanged(vcs, Seq(project), env.state)
          env.state.log.flush()
          val logs    = env.consoleBuffer.toString("UTF-8")
          changed.map(_.name) must_== Seq("core")
          logs must contain("is not under VCS baseDir")
          logs must contain(outsideBaseDir.getAbsolutePath)
          logs must contain(repo.getAbsolutePath)
        } finally TestHelpers.deleteRecursively(outsideBaseDir)
    }

    "treat root project as unchanged when no files changed since tag" in withTempDirectory { repo =>
      IO.write(repo / "version.sbt", """version := "0.1.0-SNAPSHOT"""" + "\n")

      initGitRepo(repo)
      runGit(repo, "add", ".")
      runGit(repo, "commit", "-m", "Initial commit")
      runGit(repo, "tag", "root-v0.1.0")

      val vcs = Vcs.detect(repo).getOrElse(
        throw new RuntimeException(s"Failed to detect VCS in ${repo.getAbsolutePath}")
      )

      val logFile = repo / "sbt-test.log"
      val env     = testEnv(repo, logFile)
      val project = ProjectReleaseInfo(
        ref = ProjectRef(repo.toURI, "root"),
        name = "root",
        baseDir = repo,
        versionFile = repo / "version.sbt"
      )

      val changed = detectChanged(vcs, Seq(project), env.state)
      env.state.log.flush()
      val logs    = env.consoleBuffer.toString("UTF-8")
      changed must beEmpty

      logs must contain("root unchanged since root-v0.1.0")
    }
  }

  private val perProjectTagName: (String, String) => String =
    (name, version) => s"$name-v$version"

  private val unifiedTagName: String => String =
    version => s"v$version"

  private def detectChanged(
      vcs: Vcs,
      projects: Seq[ProjectReleaseInfo],
      state: State
  ): Seq[ProjectReleaseInfo] =
    ChangeDetection
      .detectChangedProjects(
        vcs,
        projects,
        MonorepoTagStrategy.PerProject,
        perProjectTagName,
        unifiedTagName,
        state
      )
      .unsafeRunSync()

  private def withTempDirectory[A](f: File => A): A = {
    val dir = Files.createTempDirectory("change-detection-spec").toFile
    try f(dir)
    finally TestHelpers.deleteRecursively(dir)
  }

  private def initGitRepo(repo: File): Unit = {
    runGit(repo, "init")
    runGit(repo, "config", "user.email", "test@example.com")
    runGit(repo, "config", "user.name", "Test User")
    ()
  }

  private def runGit(repo: File, args: String*): String =
    Process(Seq("git") ++ args, repo).!!

  private final class TestEnv(
      val state: State,
      val consoleBuffer: ByteArrayOutputStream
  )

  private def testEnv(baseDir: File, logFile: File): TestEnv = {
    val buffer     = new ByteArrayOutputStream()
    val consoleOut = ConsoleOut.printStreamOut(new PrintStream(buffer))
    val globalLogging =
      GlobalLogging.initial(
        MainAppender.globalDefault(consoleOut),
        logFile,
        consoleOut
      )

    new TestEnv(
      state = State(
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
      ),
      consoleBuffer = buffer
    )
  }

  private def dummyAppConfiguration(baseDir: File): AppConfiguration = {
    val launcher: Launcher = new Launcher {
      override def getScala(version: String): ScalaProvider                                   = null
      override def getScala(version: String, reason: String): ScalaProvider                   = null
      override def getScala(version: String, reason: String, scalaOrg: String): ScalaProvider = null
      override def app(id: xsbti.ApplicationID, version: String): AppProvider                 = null
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

    val appId: xsbti.ApplicationID = new xsbti.ApplicationID {
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
      override def launcher(): Launcher                = launcher
      override def version(): String                   = "2.12.21"
      override def loader(): ClassLoader               = getClass.getClassLoader
      override def jars(): Array[File]                 = Array.empty
      override def libraryJar(): File                  = new File(baseDir, "scala-library.jar")
      override def compilerJar(): File                 = new File(baseDir, "scala-compiler.jar")
      override def app(id: xsbti.ApplicationID): AppProvider = appProvider
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
      override def id(): xsbti.ApplicationID        = appId
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
