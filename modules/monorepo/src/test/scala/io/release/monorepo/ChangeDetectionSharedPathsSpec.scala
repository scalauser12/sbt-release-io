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
          result  <- GitProcessSupport.runCommandResult(
                       repo,
                       Seq("describe", "--tags", "--match", "core-v*", "--abbrev=0")
                     )
          _        = assert(result.exitCode != 0)
          _        = assert(result.stderr.nonEmpty)
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

  test("detectChangedProjects - match shared-path excludes for non-ASCII paths") {
    // Pins the `-z` diff fix: with line-oriented output git C-quotes non-ASCII paths, so
    // the excluded CHANGELOG under café-common survived the exclusion filter and a false
    // shared-path change marked every project as changed. core.quotePath is forced to true
    // so the pre-fix failure reproduces regardless of the developer's global git config.
    repoResource.use { repo =>
      for {
        changelog <- IO.blocking {
                       val sharedDir = new File(repo, "café-common")
                       val file      = new File(sharedDir, "CHANGELOG.md")

                       sbt.IO.createDirectory(new File(repo, "core"))
                       sbt.IO.createDirectory(sharedDir)
                       sbt.IO.write(
                         new File(repo, "core/version.sbt"),
                         """version := "0.1.0-SNAPSHOT"""" + "\n"
                       )
                       sbt.IO.write(file, "# Changelog\n")

                       TestSupport.initGitRepo(repo)
                       TestSupport.runGit(repo, "config", "core.quotePath", "true")
                       TestSupport.runGit(repo, "add", ".")
                       TestSupport.runGit(repo, "commit", "-m", "Initial commit")
                       TestSupport.runGit(repo, "tag", "core-v0.1.0")

                       sbt.IO.write(file, "# Changelog\n\n- entry\n")
                       TestSupport.runGit(repo, "add", ".")
                       TestSupport.runGit(repo, "commit", "-m", "Update changelog")
                       file
                     }
        vcs       <- detectVcs(repo)
        env        = testEnv(repo)
        project    = nestedProject(repo, "core")
        changed   <- detectChanged(
                       vcs,
                       Seq(project),
                       env.state,
                       sharedPaths = Seq("café-common"),
                       additionalExcludeFiles = Seq(changelog)
                     )
        logs      <- readLogs(env, required = Seq("core unchanged since core-v0.1.0"))
      } yield {
        assert(changed.isEmpty)
        assert(!logs.contains("Shared path change(s) detected"))
      }
    }
  }

  test("detectChangedProjects - match trailing-slash shared paths against nested files") {
    // Pins the trailing-slash normalization in the in-Scala shared-path matcher: the
    // exclude-style matcher would append another "/" to "project/" and never match.
    repoResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core"))
        sbt.IO.createDirectory(new File(repo, "project"))
        sbt.IO.write(
          new File(repo, "core/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(new File(repo, "project/plugins.sbt"), "// plugins\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "core-v0.1.0")

        sbt.IO.write(new File(repo, "project/plugins.sbt"), "// plugins updated\n")
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Update build plugins")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val project = nestedProject(repo, "core")

        detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq("project/")).flatMap {
          changed =>
            readLogs(
              env,
              required = Seq("Shared path change(s) detected since core-v0.1.0")
            ).map { logs =>
              assertEquals(changed.map(_.name), Seq("core"))
              assert(logs.contains("project/plugins.sbt"))
            }
        }
      }
    }
  }

  test("detectChangedProjects - match shared path entries as exact files, not name prefixes") {
    repoResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core"))
        sbt.IO.write(
          new File(repo, "core/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(new File(repo, "build.sbt"), "name := \"root\"\n")
        sbt.IO.write(new File(repo, "build.sbt.bak"), "name := \"root-old\"\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "core-v0.1.0")

        sbt.IO.write(new File(repo, "build.sbt.bak"), "name := \"root-older\"\n")
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Update backup file only")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val project = nestedProject(repo, "core")

        detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq("build.sbt")).flatMap {
          changed =>
            readLogs(env, required = Seq("core unchanged since core-v0.1.0")).map { logs =>
              assert(changed.isEmpty)
              assert(!logs.contains("Shared path change(s) detected"))
            }
        }
      }
    }
  }

  test("detectChangedProjects - log the shared-path change once across differing exclude sets") {
    // Two projects share one tag but have different effective excludes (their own version
    // files), so the shared-path cache misses twice — the single cached tag diff must still
    // produce exactly one detection log line (core's evaluation excludes the changed file).
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
        ).flatMap { changed =>
          readLogs(env, required = Seq("Shared path change(s) detected")).map { logs =>
            assertEquals(changed.map(_.name), Seq("api"))
            assertEquals(
              logs.linesIterator.count(_.contains("Shared path change(s) detected")),
              1
            )
          }
        }
      }
    }
  }

  test("detectChangedProjects - conservatively mark changed when the shared-path diff fails") {
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

        sbt.IO.write(
          new File(repo, "core/version.sbt"),
          """version := "0.2.0-SNAPSHOT"""" + "\n"
        )
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Bump version")

        // Delete the tag commit's root tree object so `git describe` succeeds but the
        // full-repo `git diff <tag>..HEAD` fails (see the project-diff spec counterpart).
        val treeHash   = TestSupport.runGit(repo, "rev-parse", "core-v0.1.0^{tree}").trim
        val objectFile =
          new File(repo, s".git/objects/${treeHash.take(2)}/${treeHash.drop(2)}")
        objectFile.setWritable(true)
        assert(objectFile.delete(), s"failed to delete loose object $objectFile")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val project = nestedProject(repo, "core")

        detectChanged(vcs, Seq(project), env.state, sharedPaths = Seq("build.sbt")).flatMap {
          changed =>
            readLogs(
              env,
              required = Seq("Failed to check shared paths", "Conservatively treating as changed")
            ).map(_ => assertEquals(changed.map(_.name), Seq("core")))
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

  test("detectChangedProjects - fail when tagNameFn drops the wildcard version arg") {
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
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val project                                     = nestedProject(repo, "core")
        // Formatter that drops the version argument — simulates a misconfigured
        // releaseIOMonorepoVcsTagName whose output cannot be used as a `git tag` glob.
        val brokenFormatter: (String, String) => String =
          (name, _) => s"$name-release"

        detectChanged(vcs, Seq(project), env.state, tagNameFn = brokenFormatter).attempt.map {
          case Left(err: IllegalStateException) =>
            val msg = err.getMessage
            assert(
              msg.contains("releaseIOMonorepoVcsTagName"),
              s"expected message to mention the setting; got: $msg"
            )
            assert(msg.contains("core"), s"expected message to mention project name; got: $msg")
            assert(
              msg.contains("core-release"),
              s"expected message to include the malformed pattern; got: $msg"
            )
          case other                            =>
            fail(s"Expected IllegalStateException; got $other")
        }
      }
    }
  }

  test("detectChangedProjects - fail with a friendly error when tagNameFn throws on the wildcard") {
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
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val project                                       = nestedProject(repo, "core")
        // Formatter that strict-parses the version segment — succeeds on real
        // semvers, throws on the wildcard "*" probe. A common build-side pattern.
        val throwingFormatter: (String, String) => String =
          (name, ver) =>
            io.release.version
              .Version(ver)
              .map(v => s"$name-v${v.render}")
              .getOrElse(throw new IllegalArgumentException(s"not a semver: $ver"))

        detectChanged(vcs, Seq(project), env.state, tagNameFn = throwingFormatter).attempt.map {
          case Left(err: IllegalStateException) =>
            val msg = err.getMessage
            assert(
              msg.contains("releaseIOMonorepoVcsTagName"),
              s"expected message to mention the setting; got: $msg"
            )
            assert(msg.contains("core"), s"expected message to mention project name; got: $msg")
            assert(
              msg.contains("threw"),
              s"expected message to note the formatter threw; got: $msg"
            )
          case other                            =>
            fail(s"Expected IllegalStateException; got $other")
        }
      }
    }
  }
}
