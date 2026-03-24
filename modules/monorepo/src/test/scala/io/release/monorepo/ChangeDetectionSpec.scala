package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.TestSupport
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.ProjectRef
import sbt.State
import sbt.internal.util.AttributeMap
import sbt.internal.util.ConsoleOut
import sbt.internal.util.GlobalLogging
import sbt.internal.util.MainAppender

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
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

        detectChanged(vcs, Seq(project), env.state).flatMap { changed =>
          readLogs(env, required = Seq("git describe failed for core")).map { logs =>
            assertEquals(changed.map(_.name), Seq("core"))
            assert(logs.contains("git describe failed for core"))
            assert(!logs.contains("No previous tag matching"))
          }
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

          detectChanged(vcs, Seq(project), env.state).flatMap { changed =>
            readLogs(env, required = Seq("is not under VCS baseDir")).map { logs =>
              assertEquals(changed.map(_.name), Seq("core"))
              assert(logs.contains("is not under VCS baseDir"))
              assert(logs.contains(outsideBaseDir.getAbsolutePath))
              assert(logs.contains(repo.getAbsolutePath))
            }
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

        detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq("build.sbt")).flatMap {
          changed =>
            readLogs(env, required = Seq("Shared path change(s) detected")).map { logs =>
              assertEquals(changed.map(_.name), Seq("core"))
              assert(logs.contains("Shared path change(s) detected"))
              assert(logs.contains("build.sbt"))
            }
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

        detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq("build.sbt")).flatMap {
          changed =>
            readLogs(env, required = Seq("core unchanged since core-v0.1.0")).map { logs =>
              assert(changed.isEmpty)
              assert(logs.contains("core unchanged since core-v0.1.0"))
              assert(!logs.contains("Shared path change(s) detected"))
            }
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

        detectChanged(vcs, Seq(core, api), env.state, sharedPaths = Seq("build.sbt")).flatMap {
          changed =>
            IO(assertEquals(changed.map(_.name), Seq("core")))
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

        detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq.empty).flatMap { changed =>
          IO(assert(changed.isEmpty))
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
        ).flatMap { changed =>
          readLogs(env, required = Seq("Shared path change(s) detected")).map { logs =>
            assertEquals(changed.map(_.name), Seq("core", "api"))
            assert(logs.contains("Shared path change(s) detected"))
          }
        }
      }
    }
  }

  test("detectChangedProjects - detect project-local changes under the project directory") {
    tempDirResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core/src/main/scala"))
        sbt.IO.createDirectory(new File(repo, "api"))
        sbt.IO.write(new File(repo, "core/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
        sbt.IO.write(new File(repo, "api/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
        sbt.IO.write(new File(repo, "core/src/main/scala/Core.scala"), "object Core {}\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "core-v0.1.0")
        TestSupport.runGit(repo, "tag", "api-v0.1.0")

        sbt.IO.write(
          new File(repo, "core/src/main/scala/Core.scala"),
          "object Core { val changed = true }\n"
        )
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Update core sources")

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

        detectChanged(vcs, Seq(core, api), env.state).flatMap { changed =>
          readLogs(
            env,
            required = Seq(
              "core has 1 changed file(s) since core-v0.1.0",
              "api unchanged since api-v0.1.0"
            )
          ).map { logs =>
            assertEquals(changed.map(_.name), Seq("core"))
            assert(logs.contains("core has 1 changed file(s) since core-v0.1.0"))
            assert(logs.contains("api unchanged since api-v0.1.0"))
          }
        }
      }
    }
  }

  test("detectChangedProjects - exclude child project directories from parent project diffs") {
    tempDirResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core/src/main/scala"))
        sbt.IO.write(new File(repo, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
        sbt.IO.write(new File(repo, "core/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
        sbt.IO.write(new File(repo, "core/src/main/scala/Core.scala"), "object Core {}\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "root-v0.1.0")
        TestSupport.runGit(repo, "tag", "core-v0.1.0")

        sbt.IO.write(
          new File(repo, "core/src/main/scala/Core.scala"),
          "object Core { val changed = true }\n"
        )
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Update core sources")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo, new File(repo, "sbt-test.log"))))
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
        val root = ProjectReleaseInfo(
          ref = ProjectRef(repo.toURI, "root"),
          name = "root",
          baseDir = repo,
          versionFile = new File(repo, "version.sbt")
        )
        val core = ProjectReleaseInfo(
          ref = ProjectRef(repo.toURI, "core"),
          name = "core",
          baseDir = new File(repo, "core"),
          versionFile = new File(repo, "core/version.sbt")
        )

        detectChanged(vcs, Seq(root, core), env.state).flatMap { changed =>
          readLogs(
            env,
            required = Seq(
              "root has only version/excluded file changes since root-v0.1.0, treating as unchanged",
              "core has 1 changed file(s) since core-v0.1.0"
            )
          ).map { logs =>
            assertEquals(changed.map(_.name), Seq("core"))
            assert(
              logs.contains(
                "root has only version/excluded file changes since root-v0.1.0, treating as unchanged"
              )
            )
            assert(logs.contains("core has 1 changed file(s) since core-v0.1.0"))
          }
        }
      }
    }
  }

  test("detectChangedProjects - ignore additional excluded files beyond the version file") {
    tempDirResource.use { repo =>
      for {
        sourceFile <- IO.blocking {
                        sbt.IO.createDirectory(new File(repo, "core/src/main/scala"))
                        sbt.IO.write(
                          new File(repo, "core/version.sbt"),
                          """version := "0.1.0-SNAPSHOT"""" + "\n"
                        )
                        val sf = new File(repo, "core/src/main/scala/Core.scala")
                        sbt.IO.write(sf, "object Core {}\n")

                        TestSupport.initGitRepo(repo)
                        TestSupport.runGit(repo, "add", ".")
                        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
                        TestSupport.runGit(repo, "tag", "core-v0.1.0")

                        sbt.IO.write(sf, "object Core { val changed = true }\n")
                        TestSupport.runGit(repo, "add", ".")
                        TestSupport.runGit(repo, "commit", "-m", "Update core sources")
                        sf
                      }
        vcs        <- detectVcs(repo)
        env         = testEnv(repo, new File(repo, "sbt-test.log"))
        project     = ProjectReleaseInfo(
                        ref = ProjectRef(repo.toURI, "core"),
                        name = "core",
                        baseDir = new File(repo, "core"),
                        versionFile = new File(repo, "core/version.sbt")
                      )
        changed    <- detectChanged(
                        vcs,
                        Seq(project),
                        env.state,
                        additionalExcludeFiles = Seq(sourceFile)
                      )
        logs       <- readLogs(
                        env,
                        required = Seq(
                          "core has only version/excluded file changes since " +
                            "core-v0.1.0, treating as unchanged"
                        )
                      )
      } yield {
        assert(changed.isEmpty)
        assert(
          logs.contains(
            "core has only version/excluded file changes since " +
              "core-v0.1.0, treating as unchanged"
          )
        )
        assert(!logs.contains("core has 1 changed file(s) since core-v0.1.0"))
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

        detectChanged(vcs, Seq(project), env.state).flatMap { changed =>
          readLogs(env, required = Seq("root unchanged since root-v0.1.0")).map { logs =>
            assert(changed.isEmpty)
            assert(logs.contains("root unchanged since root-v0.1.0"))
          }
        }
      }
    }
  }

  private def readLogs(
      env: TestEnv,
      required: Seq[String] = Nil
  ): IO[String] = IO.blocking {
    val logs    = env.consoleBuffer.toString("UTF-8")
    val missing = required.filterNot(logs.contains)
    assert(missing.isEmpty, s"Missing expected log(s): ${missing.mkString(", ")}\n$logs")
    logs
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
      tagStrategy: MonorepoTagStrategy = MonorepoTagStrategy.PerProject,
      additionalExcludeFiles: Seq[File] = Seq.empty
  ): IO[Seq[ProjectReleaseInfo]] =
    ChangeDetection.detectChangedProjects(
      vcs,
      projects,
      tagStrategy,
      perProjectTagName,
      unifiedTagName,
      state,
      additionalExcludeFiles = additionalExcludeFiles,
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
