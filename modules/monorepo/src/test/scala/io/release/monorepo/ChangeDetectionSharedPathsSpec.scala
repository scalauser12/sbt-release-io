package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.TestSupport
import io.release.vcs.GitProcessSupport
import munit.CatsEffectSuite

import java.io.File

class ChangeDetectionSharedPathsSpec extends CatsEffectSuite with ChangeDetectionSpecSupport {

  test("detectChangedProjects - mark as changed when git describe fails unexpectedly") {
    repoResource.use { repo =>
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
          IO.blocking(sbt.IO.move(new File(repo, ".git"), new File(repo, ".git-broken")))
            .as((vcs, testEnv(repo)))
        }
      }.flatMap { case (vcs, env) =>
        val project = nestedProject(repo, "core")

        for {
          result <- IO.blocking(
                      GitProcessSupport.runLinesResult(
                        repo,
                        Seq("describe", "--tags", "--match", "core-v*", "--abbrev=0")
                      )
                    )
          _       = assert(result.exitCode != 0)
          _       = assert(result.stderr.nonEmpty)
          changed <- detectChanged(vcs, Seq(project), env.state)
          logs    <- readLogs(env, required = Seq("git describe failed for core"))
        } yield {
            assertEquals(changed.map(_.name), Seq("core"))
            assert(logs.contains("git describe failed for core"))
            assert(logs.contains(result.stderr))
            assert(!logs.contains("No previous tag matching"))
        }
      }
    }
  }

  test("detectChangedProjects - mark as changed when baseDir is outside VCS baseDir") {
    Resource.both(repoResource, outsideDirResource).use { case (repo: File, outsideBaseDir: File) =>
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
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val project = projectInfo(
          repo,
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

  test("detectChangedProjects - warn when child scope resolution is incomplete") {
    Resource.both(repoResource, outsideDirResource).use { case (repo: File, outsideBaseDir: File) =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "external"))
        sbt.IO.write(
          new File(repo, "version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(
          new File(repo, "external/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "root-v0.1.0")
        TestSupport.runGit(repo, "tag", "external-v0.1.0")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val root     = rootProject(repo)
        val external = projectInfo(
          repo,
          name = "external",
          baseDir = outsideBaseDir,
          versionFile = new File(repo, "external/version.sbt")
        )

        detectChanged(vcs, Seq(root, external), env.state).flatMap { changed =>
          readLogs(
            env,
            required = Seq(
              "Cannot resolve child diff scope for project(s): external",
              "Child-directory exclusion will be incomplete",
              "root unchanged since root-v0.1.0",
              "Cannot diff external: project baseDir"
            )
          ).map { logs =>
            assertEquals(changed.map(_.name), Seq("external"))
            assert(logs.contains(outsideBaseDir.getAbsolutePath))
            assert(logs.contains(repo.getAbsolutePath))
          }
        }
      }
    }
  }

  test("detectChangedProjects - mark all projects as changed when a shared path has changes") {
    repoResource.use { repo =>
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
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val project = nestedProject(repo, "core")

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
    repoResource.use { repo =>
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
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val project = nestedProject(repo, "core")

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

  test("detectChangedProjects - ignore shared path changes under excluded directories") {
    repoResource.use { repo =>
      for {
        generatedDir <- IO.blocking {
                          val dir      = new File(repo, "shared/generated")
                          val noteFile = new File(dir, "notes.txt")

                          sbt.IO.createDirectory(new File(repo, "core"))
                          sbt.IO.createDirectory(dir)
                          sbt.IO.write(
                            new File(repo, "core/version.sbt"),
                            """version := "0.1.0-SNAPSHOT"""" + "\n"
                          )
                          sbt.IO.write(noteFile, "initial note\n")

                          TestSupport.initGitRepo(repo)
                          TestSupport.runGit(repo, "add", ".")
                          TestSupport.runGit(repo, "commit", "-m", "Initial commit")
                          TestSupport.runGit(repo, "tag", "core-v0.1.0")

                          sbt.IO.write(noteFile, "updated note\n")
                          TestSupport.runGit(repo, "add", ".")
                          TestSupport.runGit(repo, "commit", "-m", "Update generated shared note")
                          dir
                        }
        vcs          <- detectVcs(repo)
        env           = testEnv(repo)
        project       = nestedProject(repo, "core")
        changed      <- detectChanged(
                          vcs,
                          Seq(project),
                          env.state,
                          sharedPaths = Seq("shared/"),
                          additionalExcludeFiles = Seq(generatedDir)
                        )
        logs         <- readLogs(env, required = Seq("core unchanged since core-v0.1.0"))
      } yield {
        assert(changed.isEmpty)
        assert(logs.contains("core unchanged since core-v0.1.0"))
        assert(!logs.contains("Shared path change(s) detected"))
      }
    }
  }

  test("detectChangedProjects - detect shared path changes per-project with diverged tags") {
    repoResource.use { repo =>
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
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val core = nestedProject(repo, "core")
        val api  = nestedProject(repo, "api")

        detectChanged(vcs, Seq(core, api), env.state, sharedPaths = Seq("build.sbt")).flatMap {
          changed =>
            IO(assertEquals(changed.map(_.name), Seq("core")))
        }
      }
    }
  }

  test("detectChangedProjects - key shared path cache by tag and effective excludes") {
    repoResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core"))
        sbt.IO.createDirectory(new File(repo, "api"))
        sbt.IO.createDirectory(new File(repo, "versions"))
        sbt.IO.write(
          new File(repo, "versions/core.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(
          new File(repo, "versions/api.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "shared-v0.1.0")

        sbt.IO.write(
          new File(repo, "versions/core.sbt"),
          """version := "0.2.0-SNAPSHOT"""" + "\n"
        )
        TestSupport.runGit(repo, "add", "versions/core.sbt")
        TestSupport.runGit(repo, "commit", "-m", "Update core version file")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val core = projectInfo(
          repo,
          name = "core",
          baseDir = new File(repo, "core"),
          versionFile = new File(repo, "versions/core.sbt")
        )
        val api  = projectInfo(
          repo,
          name = "api",
          baseDir = new File(repo, "api"),
          versionFile = new File(repo, "versions/api.sbt")
        )

        detectChanged(
          vcs,
          Seq(core, api),
          env.state,
          sharedPaths = Seq("versions/"),
          tagNameFn = (_, version) => s"shared-v$version"
        ).map { changed =>
          assertEquals(changed.map(_.name), Seq("api"))
        }
      }
    }
  }

  test("detectChangedProjects - ignore shared path changes when sharedPaths is empty") {
    repoResource.use { repo =>
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
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val project = nestedProject(repo, "core")

        detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq.empty).flatMap { changed =>
          IO(assert(changed.isEmpty))
        }
      }
    }
  }

  test("detectChangedProjects - detect shared path changes against per-project tags") {
    repoResource.use { repo =>
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
        TestSupport.runGit(repo, "tag", "api-v0.1.0")

        sbt.IO.write(new File(repo, "build.sbt"), "name := \"root-updated\"\n")
        TestSupport.runGit(repo, "add", "build.sbt")
        TestSupport.runGit(repo, "commit", "-m", "Update root build.sbt")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val core = nestedProject(repo, "core")
        val api  = nestedProject(repo, "api")

        detectChanged(vcs, Seq(core, api), env.state, sharedPaths = Seq("build.sbt")).flatMap {
          changed =>
            readLogs(env, required = Seq("Shared path change(s) detected")).map { logs =>
              assertEquals(changed.map(_.name), Seq("core", "api"))
              assert(logs.contains("Shared path change(s) detected"))
            }
        }
      }
    }
  }
}
