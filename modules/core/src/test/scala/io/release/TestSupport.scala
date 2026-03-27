package io.release

import cats.effect.IO
import cats.effect.Resource
import io.release.internal.SbtRuntime
import io.release.vcs.Vcs
import sbt.Project
import sbt.Setting
import sbt.State
import sbt.internal.util.AttributeMap
import sbt.internal.util.ConsoleOut
import sbt.internal.util.GlobalLogging
import sbt.internal.util.MainAppender
import xsbti.*

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import scala.sys.process.Process

/** Shared test fixtures for constructing minimal sbt `State` instances and test repositories. */
object TestSupport {

  val CurrentScalaVersion: String = scala.util.Properties.versionNumberString
  val Scala212TestVersion: String = "2.12.21"
  val Scala213TestVersion: String = "2.13.12"

  def alternateScalaVersion: String =
    if (CurrentScalaVersion.startsWith("2.12.")) Scala213TestVersion
    else Scala212TestVersion

  def deleteRecursively(file: File): Unit = {
    val root = file.toPath

    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) ()
    else
      Files.walkFileTree(
        root,
        new SimpleFileVisitor[Path] {
          override def visitFile(
              path: Path,
              attrs: BasicFileAttributes
          ): FileVisitResult = {
            Files.deleteIfExists(path)
            FileVisitResult.CONTINUE
          }

          override def visitFileFailed(path: Path, exc: IOException): FileVisitResult = {
            Files.deleteIfExists(path)
            FileVisitResult.CONTINUE
          }

          override def postVisitDirectory(
              dir: Path,
              exc: IOException
          ): FileVisitResult = {
            if (exc != null) throw exc
            Files.deleteIfExists(dir)
            FileVisitResult.CONTINUE
          }
        }
      )
  }

  def tempDirResource(prefix: String): Resource[IO, File] =
    Resource.make(IO.blocking(Files.createTempDirectory(prefix).toFile))(dir =>
      IO.blocking(deleteRecursively(dir))
    )

  def dummyStateResource(prefix: String): Resource[IO, State] =
    tempDirResource(prefix).evalMap(dir => IO.blocking(dummyState(dir)))

  def dummyContextResource(prefix: String): Resource[IO, ReleaseContext] =
    dummyStateResource(prefix).map(state => ReleaseContext(state = state))

  def gitRepoResource(prefix: String): Resource[IO, File] =
    tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        initGitRepo(dir)
        dir
      }
    }

  def gitRepoWithCommitResource(
      prefix: String,
      prepareRepo: File => IO[Unit] = repo =>
        IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "initial")),
      commitMessage: String = "Initial commit"
  ): Resource[IO, (File, Vcs)] =
    gitRepoResource(prefix).evalMap { repo =>
      prepareRepo(repo) *>
        IO.blocking(commitAll(repo, commitMessage)) *>
        detectVcs(repo).map(repo -> _)
    }

  def gitRootState(
      repo: File,
      rootSettings: Seq[Setting[?]] = Nil
  ): State =
    loadedState(
      repo,
      Seq(
        Project("root", repo).settings(
          (Seq(ReleaseIO.releaseIOIgnoreUntrackedFiles := false) ++ rootSettings)*
        )
      ),
      currentProjectId = Some("root")
    )

  def gitRepoWithLoadedStateResource(
      prefix: String,
      rootSettings: Seq[Setting[?]] = Nil,
      prepareRepo: File => IO[Unit] = repo =>
        IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "initial")),
      commitMessage: String = "Initial commit"
  ): Resource[IO, (File, State)] =
    gitRepoWithCommitResource(prefix, prepareRepo, commitMessage).evalMap { case (repo, _) =>
      IO.blocking(repo -> gitRootState(repo, rootSettings))
    }

  def brokenRemoteContextResource(
      prefix: String,
      interactive: Boolean = false
  ): Resource[IO, ReleaseContext] =
    tempDirResource(prefix).evalMap { repo =>
      initRepoWithBrokenRemote(repo).map { vcs =>
        ReleaseContext(
          state = gitRootState(repo),
          vcs = Some(vcs),
          interactive = interactive
        )
      }
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

  def loadedState(
      baseDir: File,
      projects: Seq[Project],
      buildSettings: Seq[Setting[?]] = Nil,
      currentProjectId: Option[String] = None
  ): State =
    sbt.TestBuildState(
      baseState = dummyState(baseDir),
      baseDir = baseDir,
      projects = projects,
      buildSettings = buildSettings,
      currentProjectId = currentProjectId
    )

  def appendSessionSettings(state: State, settings: Seq[Setting[?]]): State =
    SbtRuntime.appendWithSession(state, settings)

  private class DummyAppMain extends AppMain {
    override def run(configuration: AppConfiguration): MainResult = new MainResult {}
  }

  def dummyAppConfiguration(baseDir: File): AppConfiguration = {
    def classpathJar(className: String): File = {
      val clazz      = Class.forName(className)
      val codeSource = Option(clazz.getProtectionDomain).flatMap(pd => Option(pd.getCodeSource))
      val location   = codeSource.flatMap(cs => Option(cs.getLocation))

      location match {
        case Some(url) => new File(url.toURI)
        case None      =>
          throw new IllegalStateException(
            s"Synthetic sbt test harness requires a concrete classpath location for '$className'."
          )
      }
    }

    val runtimeLibraryJar  = classpathJar("scala.Predef$")
    val runtimeCompilerJar =
      if (CurrentScalaVersion.startsWith("3.")) classpathJar("dotty.tools.dotc.Main")
      else classpathJar("scala.tools.nsc.Main")

    def componentProvider: ComponentProvider = new ComponentProvider {
      override def componentLocation(id: String): File                     = new File(baseDir, s"component-$id")
      override def component(id: String): Array[File]                      = Array.empty
      override def defineComponent(id: String, files: Array[File]): Unit   = ()
      override def addToComponent(id: String, files: Array[File]): Boolean = true
      override def lockFile(): File                                        = new File(baseDir, ".components.lock")
    }

    def appProvider(appId0: ApplicationID, scalaProvider0: ScalaProvider): AppProvider =
      new AppProvider {
        override def scalaProvider(): ScalaProvider   = scalaProvider0
        override def id(): ApplicationID              = appId0
        override def loader(): ClassLoader            = getClass.getClassLoader
        override def mainClass(): Class[? <: AppMain] = classOf[DummyAppMain]
        override def entryPoint(): Class[?]           = classOf[DummyAppMain]
        override def newMain(): AppMain               = new DummyAppMain
        override def mainClasspath(): Array[File]     = Array.empty
        override def components(): ComponentProvider  = componentProvider
      }

    def scalaProviderFor(
        version0: String,
        appId0: ApplicationID,
        launcherRef: => Launcher
    ): ScalaProvider =
      new ScalaProvider {
        self =>
        override def launcher(): Launcher                = launcherRef
        override def version(): String                   = version0
        override def loader(): ClassLoader               = getClass.getClassLoader
        override def jars(): Array[File]                 = Array(libraryJar(), compilerJar())
        override def libraryJar(): File                  = runtimeLibraryJar
        override def compilerJar(): File                 = runtimeCompilerJar
        override def app(id: ApplicationID): AppProvider = appProvider(id, self)
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

    lazy val launcher0: Launcher = new Launcher {
      override def getScala(version: String): ScalaProvider =
        scalaProviderFor(version, appId, launcher0)

      override def getScala(version: String, reason: String): ScalaProvider =
        scalaProviderFor(version, appId, launcher0)

      override def getScala(version: String, reason: String, scalaOrg: String): ScalaProvider =
        scalaProviderFor(version, appId, launcher0)

      override def app(id: ApplicationID, version: String): AppProvider =
        appProvider(id, scalaProviderFor(version, id, launcher0))

      override def topLoader(): ClassLoader             = getClass.getClassLoader
      override def globalLock(): GlobalLock             = new GlobalLock {
        override def apply[T](lockFile: File, run: java.util.concurrent.Callable[T]): T = run.call()
      }
      override def bootDirectory(): File                = baseDir
      override def ivyRepositories(): Array[Repository] = Array.empty
      override def appRepositories(): Array[Repository] = Array.empty
      override def isOverrideRepositories(): Boolean    = false
      override def ivyHome(): File                      = baseDir
      override def checksums(): Array[String]           = Array.empty
    }

    lazy val scalaProvider0: ScalaProvider = scalaProviderFor(CurrentScalaVersion, appId, launcher0)
    lazy val appProvider0: AppProvider     = appProvider(appId, scalaProvider0)

    new AppConfiguration {
      override def arguments(): Array[String] = Array.empty
      override def baseDirectory(): File      = baseDir
      override def provider(): AppProvider    = appProvider0
    }
  }

  def initGitRepo(repo: File): Unit = {
    runGit(repo, "init")
    runGit(repo, "config", "user.email", "test@example.com")
    runGit(repo, "config", "user.name", "Test User")
    ()
  }

  def commitAll(repo: File, message: String): Unit = {
    runGit(repo, "add", ".")
    runGit(repo, "commit", "-m", message)
    ()
  }

  def runGit(repo: File, args: String*): String =
    Process(Seq("git") ++ args, repo).!!

  def detectVcs(repo: File): IO[Vcs] =
    Vcs.detect(repo).flatMap {
      case Some(vcs) => IO.pure(vcs)
      case None      =>
        IO.raiseError(new RuntimeException(s"Failed to detect VCS in ${repo.getAbsolutePath}"))
    }

  def initRepoWithBrokenRemote(repo: File): IO[Vcs] =
    IO.blocking {
      initGitRepo(repo)
      sbt.IO.write(new File(repo, "file.txt"), "initial")
      runGit(repo, "add", ".")
      runGit(repo, "commit", "-m", "Initial commit")
      runGit(repo, "branch", "-M", "main")
      runGit(repo, "remote", "add", "origin", new File(repo, "missing-remote.git").getAbsolutePath)
      runGit(repo, "config", "branch.main.remote", "origin")
      runGit(repo, "config", "branch.main.merge", "refs/heads/main")
      repo
    }.flatMap(detectVcs)
}
