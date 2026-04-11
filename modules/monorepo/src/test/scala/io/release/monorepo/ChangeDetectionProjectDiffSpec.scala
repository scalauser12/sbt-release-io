package io.release.monorepo

import cats.effect.IO
import io.release.TestSupport
import io.release.vcs.GitProcessSupport
import munit.CatsEffectSuite

import java.io.File

class ChangeDetectionProjectDiffSpec extends CatsEffectSuite with ChangeDetectionSpecSupport {

  test("detectChangedProjects - detect project-local changes under the project directory") {
    repoResource.use { repo =>
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
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val core = nestedProject(repo, "core")
        val api  = nestedProject(repo, "api")

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
    repoResource.use { repo =>
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
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val root = rootProject(repo)
        val core = nestedProject(repo, "core")

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
    repoResource.use { repo =>
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
        env         = testEnv(repo)
        project     = nestedProject(repo, "core")
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

  test("detectChangedProjects - ignore additional excluded directories beyond the version file") {
    repoResource.use { repo =>
      for {
        docsDir <- IO.blocking {
                     val dir    = new File(repo, "core/docs")
                     sbt.IO.createDirectory(dir)
                     sbt.IO.write(
                       new File(repo, "core/version.sbt"),
                       """version := "0.1.0-SNAPSHOT"""" + "\n"
                     )
                     val readme = new File(dir, "README.md")
                     sbt.IO.write(readme, "# Core docs\n")

                     TestSupport.initGitRepo(repo)
                     TestSupport.runGit(repo, "add", ".")
                     TestSupport.runGit(repo, "commit", "-m", "Initial commit")
                     TestSupport.runGit(repo, "tag", "core-v0.1.0")

                     sbt.IO.write(readme, "# Updated core docs\n")
                     TestSupport.runGit(repo, "add", ".")
                     TestSupport.runGit(repo, "commit", "-m", "Update docs")
                     dir
                   }
        vcs     <- detectVcs(repo)
        env      = testEnv(repo)
        project  = nestedProject(repo, "core")
        changed <- detectChanged(
                     vcs,
                     Seq(project),
                     env.state,
                     additionalExcludeFiles = Seq(docsDir)
                   )
        logs    <- readLogs(
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
      }
    }
  }

  test("detectChangedProjects - keep sibling changes when excluding a directory") {
    repoResource.use { repo =>
      for {
        docsDir <- IO.blocking {
                     val dir        = new File(repo, "core/docs")
                     val sourceDir  = new File(repo, "core/src/main/scala")
                     val versionSbt = new File(repo, "core/version.sbt")
                     val readme     = new File(dir, "README.md")
                     val sourceFile = new File(sourceDir, "Core.scala")

                     sbt.IO.createDirectory(dir)
                     sbt.IO.createDirectory(sourceDir)
                     sbt.IO.write(versionSbt, """version := "0.1.0-SNAPSHOT"""" + "\n")
                     sbt.IO.write(readme, "# Core docs\n")
                     sbt.IO.write(sourceFile, "object Core {}\n")

                     TestSupport.initGitRepo(repo)
                     TestSupport.runGit(repo, "add", ".")
                     TestSupport.runGit(repo, "commit", "-m", "Initial commit")
                     TestSupport.runGit(repo, "tag", "core-v0.1.0")

                     sbt.IO.write(readme, "# Updated core docs\n")
                     sbt.IO.write(sourceFile, "object Core { val changed = true }\n")
                     TestSupport.runGit(repo, "add", ".")
                     TestSupport.runGit(repo, "commit", "-m", "Update docs and sources")
                     dir
                   }
        vcs     <- detectVcs(repo)
        env      = testEnv(repo)
        project  = nestedProject(repo, "core")
        changed <- detectChanged(
                     vcs,
                     Seq(project),
                     env.state,
                     additionalExcludeFiles = Seq(docsDir)
                   )
        logs    <- readLogs(
                     env,
                     required = Seq(
                       "core has 1 changed file(s) since core-v0.1.0 " +
                         "(1 version/excluded file(s) filtered)"
                     )
                   )
      } yield {
        assertEquals(changed.map(_.name), Seq("core"))
        assert(
          logs.contains(
            "core has 1 changed file(s) since core-v0.1.0 " +
              "(1 version/excluded file(s) filtered)"
          )
        )
      }
    }
  }

  test("detectChangedProjects - mark as changed when git diff fails unexpectedly") {
    repoResource.use { repo =>
      IO.blocking {
        val badDir = new File(repo, ":(badmagic)")

        sbt.IO.createDirectory(badDir)
        sbt.IO.write(
          new File(badDir, "version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(new File(badDir, "Magic.scala"), "object Magic {}\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "magic-v0.1.0")

        sbt.IO.write(new File(badDir, "Magic.scala"), "object Magic { val changed = true }\n")
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Update magic sources")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val badDir  = new File(repo, ":(badmagic)")
        val project = projectInfo(
          repo,
          name = "magic",
          baseDir = badDir,
          versionFile = new File(badDir, "version.sbt")
        )

        for {
          result <- IO.blocking(
                      GitProcessSupport.runLinesResult(
                        repo,
                        Seq("diff", "--name-only", "magic-v0.1.0..HEAD", "--", ":(badmagic)")
                      )
                    )
          _       = assert(result.exitCode != 0)
          _       = assert(result.stderr.nonEmpty)
          changed <- detectChanged(vcs, Seq(project), env.state)
          logs    <- readLogs(env, required = Seq("git diff failed for magic"))
        } yield {
            assertEquals(changed.map(_.name), Seq("magic"))
            assert(logs.contains("git diff failed for magic"))
            assert(logs.contains(result.stderr))
        }
      }
    }
  }

  test("detectChangedProjects - treat root project as unchanged when no files changed") {
    repoResource.use { repo =>
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
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val project = rootProject(repo)

        detectChanged(vcs, Seq(project), env.state).flatMap { changed =>
          readLogs(env, required = Seq("root unchanged since root-v0.1.0")).map { logs =>
            assert(changed.isEmpty)
            assert(logs.contains("root unchanged since root-v0.1.0"))
          }
        }
      }
    }
  }
}
