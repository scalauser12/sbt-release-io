package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.TestSupport
import io.release.vcs.Vcs
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
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
        val project = nestedProject(repo, "core")

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
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
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
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
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
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
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
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
        val core = nestedProject(repo, "core")
        val api  = nestedProject(repo, "api")

        detectChanged(vcs, Seq(core, api), env.state, sharedPaths = Seq("build.sbt")).flatMap {
          changed =>
            IO(assertEquals(changed.map(_.name), Seq("core")))
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
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
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
      }.flatMap { case (vcs: Vcs, env: TestEnv) =>
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

