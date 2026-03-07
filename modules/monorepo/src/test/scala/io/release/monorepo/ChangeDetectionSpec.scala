package io.release.monorepo

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Resource}
import io.release.TestSupport
import org.specs2.mutable.Specification
import sbt.internal.util.{AttributeMap, ConsoleOut, GlobalLogging, MainAppender}
import sbt.{ProjectRef, State}
import sbtrelease.Vcs

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

    "mark all projects as changed when a shared path has changes" in {
      tempDirResource.use { repo =>
        IO.blocking {
          sbt.IO.createDirectory(new File(repo, "core"))
          sbt.IO.write(new File(repo, "core/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
          sbt.IO.write(new File(repo, "build.sbt"), "name := \"root\"\n")

          initGitRepo(repo)
          runGit(repo, "add", ".")
          runGit(repo, "commit", "-m", "Initial commit")
          runGit(repo, "tag", "core-v0.1.0")

          // Modify only the root build.sbt after tagging
          sbt.IO.write(new File(repo, "build.sbt"), "name := \"root-updated\"\n")
          runGit(repo, "add", "build.sbt")
          runGit(repo, "commit", "-m", "Update root build.sbt")

          val vcs = Vcs
            .detect(repo)
            .getOrElse(sys.error(s"Failed to detect VCS in ${repo.getAbsolutePath}"))
          (vcs, testEnv(repo, new File(repo, "sbt-test.log")))
        }.flatMap { case (vcs: Vcs, env: TestEnv) =>
          val project = ProjectReleaseInfo(
            ref = ProjectRef(repo.toURI, "core"),
            name = "core",
            baseDir = new File(repo, "core"),
            versionFile = new File(repo, "core/version.sbt")
          )

          detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq("build.sbt")).map {
            changed =>
              env.state.log.flush()
              val logs = env.consoleBuffer.toString("UTF-8")
              (changed.map(_.name) must_== Seq("core")) and
                (logs must contain("Shared path change(s) detected")) and
                (logs must contain("build.sbt"))
          }
        }
      }
    }

    "not mark projects as changed when shared paths have no changes" in {
      tempDirResource.use { repo =>
        IO.blocking {
          sbt.IO.createDirectory(new File(repo, "core"))
          sbt.IO.write(new File(repo, "core/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
          sbt.IO.write(new File(repo, "build.sbt"), "name := \"root\"\n")

          initGitRepo(repo)
          runGit(repo, "add", ".")
          runGit(repo, "commit", "-m", "Initial commit")
          runGit(repo, "tag", "core-v0.1.0")

          val vcs = Vcs
            .detect(repo)
            .getOrElse(sys.error(s"Failed to detect VCS in ${repo.getAbsolutePath}"))
          (vcs, testEnv(repo, new File(repo, "sbt-test.log")))
        }.flatMap { case (vcs: Vcs, env: TestEnv) =>
          val project = ProjectReleaseInfo(
            ref = ProjectRef(repo.toURI, "core"),
            name = "core",
            baseDir = new File(repo, "core"),
            versionFile = new File(repo, "core/version.sbt")
          )

          detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq("build.sbt")).map {
            changed =>
              env.state.log.flush()
              val logs = env.consoleBuffer.toString("UTF-8")
              (changed must beEmpty) and
                (logs must not(contain("Shared path change(s) detected")))
          }
        }
      }
    }

    "detect shared path changes per-project with diverged tags" in {
      tempDirResource.use { repo =>
        IO.blocking {
          sbt.IO.createDirectory(new File(repo, "core"))
          sbt.IO.createDirectory(new File(repo, "api"))
          sbt.IO.write(new File(repo, "core/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
          sbt.IO.write(new File(repo, "api/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
          sbt.IO.write(new File(repo, "build.sbt"), "name := \"root\"\n")

          initGitRepo(repo)
          runGit(repo, "add", ".")
          runGit(repo, "commit", "-m", "Initial commit")

          // Tag core first
          runGit(repo, "tag", "core-v0.1.0")

          // Modify build.sbt after core's tag
          sbt.IO.write(new File(repo, "build.sbt"), "name := \"root-updated\"\n")
          runGit(repo, "add", "build.sbt")
          runGit(repo, "commit", "-m", "Update root build.sbt")

          // Tag api after the build.sbt change
          runGit(repo, "tag", "api-v0.1.0")

          val vcs = Vcs
            .detect(repo)
            .getOrElse(sys.error(s"Failed to detect VCS in ${repo.getAbsolutePath}"))
          (vcs, testEnv(repo, new File(repo, "sbt-test.log")))
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

          // core was tagged before build.sbt changed → core should be changed
          // api was tagged after build.sbt changed → api should be unchanged
          detectChanged(vcs, Seq(core, api), env.state, sharedPaths = Seq("build.sbt")).map {
            changed =>
              env.state.log.flush()
              changed.map(_.name) must_== Seq("core")
          }
        }
      }
    }

    "ignore shared path changes when sharedPaths is empty" in {
      tempDirResource.use { repo =>
        IO.blocking {
          sbt.IO.createDirectory(new File(repo, "core"))
          sbt.IO.write(new File(repo, "core/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
          sbt.IO.write(new File(repo, "build.sbt"), "name := \"root\"\n")

          initGitRepo(repo)
          runGit(repo, "add", ".")
          runGit(repo, "commit", "-m", "Initial commit")
          runGit(repo, "tag", "core-v0.1.0")

          // Modify root build.sbt
          sbt.IO.write(new File(repo, "build.sbt"), "name := \"root-updated\"\n")
          runGit(repo, "add", "build.sbt")
          runGit(repo, "commit", "-m", "Update root build.sbt")

          val vcs = Vcs
            .detect(repo)
            .getOrElse(sys.error(s"Failed to detect VCS in ${repo.getAbsolutePath}"))
          (vcs, testEnv(repo, new File(repo, "sbt-test.log")))
        }.flatMap { case (vcs: Vcs, env: TestEnv) =>
          val project = ProjectReleaseInfo(
            ref = ProjectRef(repo.toURI, "core"),
            name = "core",
            baseDir = new File(repo, "core"),
            versionFile = new File(repo, "core/version.sbt")
          )

          // sharedPaths is empty, so build.sbt change should be invisible
          detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq.empty).map { changed =>
            env.state.log.flush()
            (changed must beEmpty)
          }
        }
      }
    }

    "detect shared path changes in unified tag mode" in {
      tempDirResource.use { repo =>
        IO.blocking {
          sbt.IO.createDirectory(new File(repo, "core"))
          sbt.IO.createDirectory(new File(repo, "api"))
          sbt.IO.write(new File(repo, "core/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
          sbt.IO.write(new File(repo, "api/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
          sbt.IO.write(new File(repo, "build.sbt"), "name := \"root\"\n")

          initGitRepo(repo)
          runGit(repo, "add", ".")
          runGit(repo, "commit", "-m", "Initial commit")
          runGit(repo, "tag", "v0.1.0")

          // Modify build.sbt after the unified tag
          sbt.IO.write(new File(repo, "build.sbt"), "name := \"root-updated\"\n")
          runGit(repo, "add", "build.sbt")
          runGit(repo, "commit", "-m", "Update root build.sbt")

          val vcs = Vcs
            .detect(repo)
            .getOrElse(sys.error(s"Failed to detect VCS in ${repo.getAbsolutePath}"))
          (vcs, testEnv(repo, new File(repo, "sbt-test.log")))
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

          // Unified tag → both projects share the same tag → both should be changed
          detectChanged(
            vcs,
            Seq(core, api),
            env.state,
            sharedPaths = Seq("build.sbt"),
            tagStrategy = MonorepoTagStrategy.Unified
          ).map { changed =>
            env.state.log.flush()
            val logs = env.consoleBuffer.toString("UTF-8")
            (changed.map(_.name) must_== Seq("core", "api")) and
              (logs must contain("Shared path change(s) detected"))
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

  private val tempDirResource: Resource[IO, File] =
    Resource.make(IO.blocking(Files.createTempDirectory("change-detection-spec").toFile))(dir =>
      IO.blocking(TestSupport.deleteRecursively(dir))
    )

  private val outsideDirResource: Resource[IO, File] =
    Resource.make(IO.blocking(Files.createTempDirectory("change-detection-outside").toFile))(dir =>
      IO.blocking(TestSupport.deleteRecursively(dir))
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
