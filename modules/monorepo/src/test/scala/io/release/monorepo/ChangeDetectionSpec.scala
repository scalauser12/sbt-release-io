package io.release.monorepo

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Resource}
import org.specs2.mutable.Specification
import sbt.internal.util.{AttributeMap, ConsoleOut, GlobalLogging, MainAppender}
import sbt.{ProjectRef, State}
import sbtrelease.Vcs
import xsbti.*

import java.io.{ByteArrayOutputStream, File, PrintStream}
import java.nio.file.Files
import scala.sys.process.Process

class ChangeDetectionSpec extends Specification with CatsEffect {

  "ChangeDetection.detectChangedProjects" should {

    "mark project as changed and log warning when git describe fails unexpectedly" in {
      tempDirResource.use { repo =>
        IO.blocking {
          sbt.IO.createDirectory(new File(repo, "core"))
          sbt.IO.write(new File(repo, "core/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")

          initGitRepo(repo)
          runGit(repo, "add", ".")
          runGit(repo, "commit", "-m", "Initial commit")
          runGit(repo, "tag", "core-v0.1.0")

          val vcs = Vcs
            .detect(repo)
            .getOrElse(
              sys.error(s"Failed to detect VCS in ${repo.getAbsolutePath}")
            )

          sbt.IO.move(new File(repo, ".git"), new File(repo, ".git-broken"))
          (vcs, testEnv(repo, new File(repo, "sbt-test.log")))
        }.flatMap { case (vcs: Vcs, env: TestEnv) =>
          val project = ProjectReleaseInfo(
            ref = ProjectRef(repo.toURI, "core"),
            name = "core",
            baseDir = new File(repo, "core"),
            versionFile = new File(repo, "core/version.sbt")
          )

          detectChanged(vcs, Seq(project), env.state).map { changed =>
            env.state.log.flush()
            val logs = env.consoleBuffer.toString("UTF-8")
            (changed.map(_.name) must_== Seq("core")) and
              (logs must contain("git describe failed for core")) and
              (logs must not(contain("No previous tag matching")))
          }
        }
      }
    }

    "mark project as changed when project baseDir is outside VCS baseDir" in {
      Resource.both(tempDirResource, outsideDirResource).use {
        case (repo: File, outsideBaseDir: File) =>
          IO.blocking {
            sbt.IO.createDirectory(new File(repo, "core"))
            sbt.IO.write(
              new File(repo, "core/version.sbt"),
              """version := "0.1.0-SNAPSHOT"""" + "\n"
            )

            initGitRepo(repo)
            runGit(repo, "add", ".")
            runGit(repo, "commit", "-m", "Initial commit")
            runGit(repo, "tag", "core-v0.1.0")

            val vcs = Vcs
              .detect(repo)
              .getOrElse(
                sys.error(s"Failed to detect VCS in ${repo.getAbsolutePath}")
              )
            (vcs, testEnv(repo, new File(repo, "sbt-test.log")))
          }.flatMap { case (vcs: Vcs, env: TestEnv) =>
            val project = ProjectReleaseInfo(
              ref = ProjectRef(repo.toURI, "core"),
              name = "core",
              baseDir = outsideBaseDir,
              versionFile = new File(repo, "core/version.sbt")
            )

            detectChanged(vcs, Seq(project), env.state).map { changed =>
              env.state.log.flush()
              val logs = env.consoleBuffer.toString("UTF-8")
              (changed.map(_.name) must_== Seq("core")) and
                (logs must contain("is not under VCS baseDir")) and
                (logs must contain(outsideBaseDir.getAbsolutePath)) and
                (logs must contain(repo.getAbsolutePath))
            }
          }
      }
    }

    "treat root project as unchanged when no files changed since tag" in {
      tempDirResource.use { repo =>
        IO.blocking {
          sbt.IO.write(new File(repo, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")

          initGitRepo(repo)
          runGit(repo, "add", ".")
          runGit(repo, "commit", "-m", "Initial commit")
          runGit(repo, "tag", "root-v0.1.0")

          val vcs = Vcs
            .detect(repo)
            .getOrElse(
              sys.error(s"Failed to detect VCS in ${repo.getAbsolutePath}")
            )
          (vcs, testEnv(repo, new File(repo, "sbt-test.log")))
        }.flatMap { case (vcs: Vcs, env: TestEnv) =>
          val project = ProjectReleaseInfo(
            ref = ProjectRef(repo.toURI, "root"),
            name = "root",
            baseDir = repo,
            versionFile = new File(repo, "version.sbt")
          )

          detectChanged(vcs, Seq(project), env.state).map { changed =>
            env.state.log.flush()
            val logs = env.consoleBuffer.toString("UTF-8")
            (changed must beEmpty) and
              (logs must contain("root unchanged since root-v0.1.0"))
          }
        }
      }
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
  ): IO[Seq[ProjectReleaseInfo]] =
    ChangeDetection.detectChangedProjects(
      vcs,
      projects,
      MonorepoTagStrategy.PerProject,
      perProjectTagName,
      unifiedTagName,
      state
    )

  private val tempDirResource: Resource[IO, File] =
    Resource.make(IO(Files.createTempDirectory("change-detection-spec").toFile))(dir =>
      IO(TestHelpers.deleteRecursively(dir))
    )

  private val outsideDirResource: Resource[IO, File] =
    Resource.make(IO(Files.createTempDirectory("change-detection-outside").toFile))(dir =>
      IO(TestHelpers.deleteRecursively(dir))
    )

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
    val buffer        = new ByteArrayOutputStream()
    val consoleOut    = ConsoleOut.printStreamOut(new PrintStream(buffer))
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

  private class DummyAppMain extends AppMain {
    override def run(configuration: AppConfiguration): MainResult = new MainResult {}
  }

  private def dummyAppConfiguration(baseDir: File): AppConfiguration = {
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
