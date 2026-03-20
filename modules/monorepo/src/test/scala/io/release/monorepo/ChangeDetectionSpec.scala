package io.release.monorepo

import cats.effect.{IO, Resource}
import io.release.TestSupport
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.internal.util.{AttributeMap, ConsoleOut, GlobalLogging, MainAppender}
import sbt.{ProjectRef, State}

import java.io.{ByteArrayOutputStream, File, PrintStream}
import java.nio.file.Files

class ChangeDetectionSpec extends CatsEffectSuite {

  test("detectChangedProjects - mark as changed when git describe fails unexpectedly") {
    tempDirResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core"))
        sbt.IO.write(
          new File(repo, "core/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "core-v0.1.0")

        repo
      }.flatMap { _ =>
        detectVcs(repo).flatMap { vcs =>
          IO.blocking(
            sbt.IO.move(new File(repo, ".git"), new File(repo, ".git-broken"))
          ).as((vcs, testEnv(repo, new File(repo, "sbt-test.log"))))
        }
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
        val project = ProjectReleaseInfo(
          ref = ProjectRef(repo.toURI, "core"),
          name = "core",
          baseDir = new File(repo, "core"),
          versionFile = new File(repo, "core/version.sbt")
        )

        detectChanged(vcs, Seq(project), env.state).map { changed =>
          Thread.sleep(50) // allow async log appender to flush
          val logs = env.consoleBuffer.toString("UTF-8")
          assertEquals(changed.map(_.name), Seq("core"))
          assert(logs.contains("git describe failed for core"))
          assert(!logs.contains("No previous tag matching"))
        }
      }
    }
  }

  test("detectChangedProjects - mark as changed when baseDir is outside VCS baseDir") {
    Resource.both(tempDirResource, outsideDirResource).use {
      case (repo: File, outsideBaseDir: File) =>
        IO.blocking {
          sbt.IO.createDirectory(new File(repo, "core"))
          sbt.IO.write(
            new File(repo, "core/version.sbt"),
            """version := "0.1.0-SNAPSHOT"""" + "\n"
          )

          TestSupport.initGitRepo(repo)
          TestSupport.runGit(repo, "add", ".")
          TestSupport.runGit(repo, "commit", "-m", "Initial commit")
          TestSupport.runGit(repo, "tag", "core-v0.1.0")

          repo
        }.flatMap { _ =>
          detectVcs(repo).map(vcs => (vcs, testEnv(repo, new File(repo, "sbt-test.log"))))
        }.flatMap { case (vcs: Vcs, env: TestEnv) =>
          val project = ProjectReleaseInfo(
            ref = ProjectRef(repo.toURI, "core"),
            name = "core",
            baseDir = outsideBaseDir,
            versionFile = new File(repo, "core/version.sbt")
          )

          detectChanged(vcs, Seq(project), env.state).map { changed =>
            Thread.sleep(50) // allow async log appender to flush
            val logs = env.consoleBuffer.toString("UTF-8")
            assertEquals(changed.map(_.name), Seq("core"))
            assert(logs.contains("is not under VCS baseDir"))
            assert(logs.contains(outsideBaseDir.getAbsolutePath))
            assert(logs.contains(repo.getAbsolutePath))
          }
        }
    }
  }

  test("detectChangedProjects - mark all projects as changed when a shared path has changes") {
    tempDirResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core"))
        sbt.IO.write(
          new File(repo, "core/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(new File(repo, "build.sbt"), "name := \"root\"\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "core-v0.1.0")

        sbt.IO.write(new File(repo, "build.sbt"), "name := \"root-updated\"\n")
        TestSupport.runGit(repo, "add", "build.sbt")
        TestSupport.runGit(repo, "commit", "-m", "Update root build.sbt")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo, new File(repo, "sbt-test.log"))))
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
        val project = ProjectReleaseInfo(
          ref = ProjectRef(repo.toURI, "core"),
          name = "core",
          baseDir = new File(repo, "core"),
          versionFile = new File(repo, "core/version.sbt")
        )

        detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq("build.sbt")).map { changed =>
          Thread.sleep(50) // allow async log appender to flush
          val logs = env.consoleBuffer.toString("UTF-8")
          assertEquals(changed.map(_.name), Seq("core"))
          assert(logs.contains("Shared path change(s) detected"))
          assert(logs.contains("build.sbt"))
        }
      }
    }
  }

  test("detectChangedProjects - not mark projects as changed when shared paths have no changes") {
    tempDirResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core"))
        sbt.IO.write(
          new File(repo, "core/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(new File(repo, "build.sbt"), "name := \"root\"\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "core-v0.1.0")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo, new File(repo, "sbt-test.log"))))
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
        val project = ProjectReleaseInfo(
          ref = ProjectRef(repo.toURI, "core"),
          name = "core",
          baseDir = new File(repo, "core"),
          versionFile = new File(repo, "core/version.sbt")
        )

        detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq("build.sbt")).map { changed =>
          Thread.sleep(50) // allow async log appender to flush
          val logs = env.consoleBuffer.toString("UTF-8")
          assert(changed.isEmpty)
          assert(!logs.contains("Shared path change(s) detected"))
        }
      }
    }
  }

  test("detectChangedProjects - detect shared path changes per-project with diverged tags") {
    tempDirResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core"))
        sbt.IO.createDirectory(new File(repo, "api"))
        sbt.IO.write(
          new File(repo, "core/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(
          new File(repo, "api/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(new File(repo, "build.sbt"), "name := \"root\"\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")

        TestSupport.runGit(repo, "tag", "core-v0.1.0")

        sbt.IO.write(new File(repo, "build.sbt"), "name := \"root-updated\"\n")
        TestSupport.runGit(repo, "add", "build.sbt")
        TestSupport.runGit(repo, "commit", "-m", "Update root build.sbt")

        TestSupport.runGit(repo, "tag", "api-v0.1.0")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo, new File(repo, "sbt-test.log"))))
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
        val core = ProjectReleaseInfo(
          ref = ProjectRef(repo.toURI, "core"),
          name = "core",
          baseDir = new File(repo, "core"),
          versionFile = new File(repo, "core/version.sbt")
        )
        val api  = ProjectReleaseInfo(
          ref = ProjectRef(repo.toURI, "api"),
          name = "api",
          baseDir = new File(repo, "api"),
          versionFile = new File(repo, "api/version.sbt")
        )

        detectChanged(vcs, Seq(core, api), env.state, sharedPaths = Seq("build.sbt")).map {
          changed =>
            Thread.sleep(50) // allow async log appender to flush
            assertEquals(changed.map(_.name), Seq("core"))
        }
      }
    }
  }

  test("detectChangedProjects - ignore shared path changes when sharedPaths is empty") {
    tempDirResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core"))
        sbt.IO.write(
          new File(repo, "core/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(new File(repo, "build.sbt"), "name := \"root\"\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "core-v0.1.0")

        sbt.IO.write(new File(repo, "build.sbt"), "name := \"root-updated\"\n")
        TestSupport.runGit(repo, "add", "build.sbt")
        TestSupport.runGit(repo, "commit", "-m", "Update root build.sbt")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo, new File(repo, "sbt-test.log"))))
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
        val project = ProjectReleaseInfo(
          ref = ProjectRef(repo.toURI, "core"),
          name = "core",
          baseDir = new File(repo, "core"),
          versionFile = new File(repo, "core/version.sbt")
        )

        detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq.empty).map { changed =>
          Thread.sleep(50) // allow async log appender to flush
          assert(changed.isEmpty)
        }
      }
    }
  }

  test("detectChangedProjects - detect shared path changes in unified tag mode") {
    tempDirResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core"))
        sbt.IO.createDirectory(new File(repo, "api"))
        sbt.IO.write(
          new File(repo, "core/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(
          new File(repo, "api/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(new File(repo, "build.sbt"), "name := \"root\"\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "v0.1.0")

        sbt.IO.write(new File(repo, "build.sbt"), "name := \"root-updated\"\n")
        TestSupport.runGit(repo, "add", "build.sbt")
        TestSupport.runGit(repo, "commit", "-m", "Update root build.sbt")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo, new File(repo, "sbt-test.log"))))
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
        val core = ProjectReleaseInfo(
          ref = ProjectRef(repo.toURI, "core"),
          name = "core",
          baseDir = new File(repo, "core"),
          versionFile = new File(repo, "core/version.sbt")
        )
        val api  = ProjectReleaseInfo(
          ref = ProjectRef(repo.toURI, "api"),
          name = "api",
          baseDir = new File(repo, "api"),
          versionFile = new File(repo, "api/version.sbt")
        )

        detectChanged(
          vcs,
          Seq(core, api),
          env.state,
          sharedPaths = Seq("build.sbt"),
          tagStrategy = MonorepoTagStrategy.Unified
        ).map { changed =>
          Thread.sleep(50) // allow async log appender to flush
          val logs = env.consoleBuffer.toString("UTF-8")
          assertEquals(changed.map(_.name), Seq("core", "api"))
          assert(logs.contains("Shared path change(s) detected"))
        }
      }
    }
  }

  test("detectChangedProjects - treat root project as unchanged when no files changed") {
    tempDirResource.use { repo =>
      IO.blocking {
        sbt.IO.write(
          new File(repo, "version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "root-v0.1.0")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo, new File(repo, "sbt-test.log"))))
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
        val project = ProjectReleaseInfo(
          ref = ProjectRef(repo.toURI, "root"),
          name = "root",
          baseDir = repo,
          versionFile = new File(repo, "version.sbt")
        )

        detectChanged(vcs, Seq(project), env.state).map { changed =>
          Thread.sleep(50) // allow async log appender to flush
          val logs = env.consoleBuffer.toString("UTF-8")
          assert(changed.isEmpty)
          assert(logs.contains("root unchanged since root-v0.1.0"))
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
      state: State,
      sharedPaths: Seq[String] = Seq.empty,
      tagStrategy: MonorepoTagStrategy = MonorepoTagStrategy.PerProject
  ): IO[Seq[ProjectReleaseInfo]] =
    ChangeDetection.detectChangedProjects(
      vcs,
      projects,
      tagStrategy,
      perProjectTagName,
      unifiedTagName,
      state,
      sharedPaths = sharedPaths
    )

  private def detectVcs(repo: File): IO[Vcs] =
    Vcs.detect(repo).flatMap {
      case Some(vcs) => IO.pure(vcs)
      case None      =>
        IO.raiseError(
          new RuntimeException(s"Failed to detect VCS in ${repo.getAbsolutePath}")
        )
    }

  private val tempDirResource: Resource[IO, File] =
    Resource.make(
      IO.blocking(Files.createTempDirectory("change-detection-spec").toFile)
    )(dir => IO.blocking(TestSupport.deleteRecursively(dir)))

  private val outsideDirResource: Resource[IO, File] =
    Resource.make(
      IO.blocking(Files.createTempDirectory("change-detection-outside").toFile)
    )(dir => IO.blocking(TestSupport.deleteRecursively(dir)))

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
        configuration = TestSupport.dummyAppConfiguration(baseDir),
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
}
