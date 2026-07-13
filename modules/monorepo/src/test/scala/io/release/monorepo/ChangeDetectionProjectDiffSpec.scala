package io.release.monorepo

import cats.effect.IO
import io.release.TestSupport
import io.release.monorepo.internal.ChangeDetection
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

  test("detectChangedProjects - exclude loaded child omitted from release participation") {
    repoResource.use { repo =>
      IO.blocking {
        val childDir = new File(repo, "tools/fixture")
        sbt.IO.createDirectory(childDir)
        sbt.IO.write(new File(repo, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
        sbt.IO.write(new File(childDir, "Fixture.scala"), "object Fixture {}\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "root-v0.1.0")

        sbt.IO.write(
          new File(childDir, "Fixture.scala"),
          "object Fixture { val changed = true }\n"
        )
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Update nonparticipating child")
      } *> detectVcs(repo).map(vcs => (vcs, testEnv(repo))).flatMap { case (vcs, env) =>
        val root     = rootProject(repo)
        // Use the same project id in a distinct build to pin ProjectRef-based scope keys.
        val childRef = sbt.ProjectRef(new File(repo, "tools").toURI, "root")

        detectChanged(
          vcs,
          Seq(root),
          env.state,
          loadedProjectBaseDirs = Map(
            root.ref -> root.baseDir,
            childRef -> new File(repo, "tools/fixture")
          )
        ).flatMap { changed =>
          readLogs(
            env,
            required = Seq(
              "root has only version/excluded file changes since " +
                "root-v0.1.0, treating as unchanged"
            )
          ).map(_ => assert(changed.isEmpty))
        }
      }
    }
  }

  test("detectChangedProjects - exclude a loaded strict descendant from a nested parent") {
    repoResource.use { repo =>
      IO.blocking {
        val toolsDir   = new File(repo, "tools")
        val fixtureDir = new File(toolsDir, "fixture")
        sbt.IO.createDirectory(fixtureDir)
        sbt.IO.write(
          new File(toolsDir, "version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(new File(fixtureDir, "Fixture.scala"), "object Fixture {}\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "tools-v0.1.0")

        sbt.IO.write(
          new File(fixtureDir, "Fixture.scala"),
          "object Fixture { val changed = true }\n"
        )
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Update nested fixture")
      } *> detectVcs(repo).map(vcs => (vcs, testEnv(repo))).flatMap { case (vcs, env) =>
        val tools      = projectInfo(
          repo,
          name = "tools",
          baseDir = new File(repo, "tools"),
          versionFile = new File(repo, "tools/version.sbt")
        )
        val fixtureRef = sbt.ProjectRef(repo.toURI, "fixture")

        detectChanged(
          vcs,
          Seq(tools),
          env.state,
          loadedProjectBaseDirs = Map(
            tools.ref  -> tools.baseDir,
            fixtureRef -> new File(repo, "tools/fixture")
          )
        ).map(changed => assert(changed.isEmpty))
      }
    }
  }

  test("detectChangedProjects - do not treat a loaded project with the same base as a child") {
    repoResource.use { repo =>
      IO.blocking {
        sbt.IO.write(new File(repo, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
        sbt.IO.write(new File(repo, "README.md"), "# Initial\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "root-v0.1.0")

        sbt.IO.write(new File(repo, "README.md"), "# Changed\n")
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Update root readme")
      } *> detectVcs(repo).map(vcs => (vcs, testEnv(repo))).flatMap { case (vcs, env) =>
        val root     = rootProject(repo)
        val aliasRef = sbt.ProjectRef(new File(repo, "alias-build").toURI, "alias")

        detectChanged(
          vcs,
          Seq(root),
          env.state,
          loadedProjectBaseDirs = Map(root.ref -> repo, aliasRef -> repo)
        ).map { changed =>
          assertEquals(changed.map(_.name), Seq("root"))
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

  // The non-ASCII directory tests pin the `-z` diff fix: with line-oriented output git
  // C-quotes such paths, defeating the ==/startsWith exclusion filters, so version-file-only
  // changes counted as significant and child changes were mis-attributed to parent scopes.
  // core.quotePath is forced to true so the pre-fix failure reproduces regardless of the
  // developer's global git config. Project names stay ASCII so tag globs are unaffected.
  test(
    "detectChangedProjects - treat version-file-only changes as unchanged for a " +
      "non-ASCII project dir"
  ) {
    repoResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "café"))
        sbt.IO.write(new File(repo, "café/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "config", "core.quotePath", "true")
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "cafe-v0.1.0")

        sbt.IO.write(new File(repo, "café/version.sbt"), """version := "0.2.0-SNAPSHOT"""" + "\n")
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Bump version")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val project = projectInfo(
          repo,
          name = "cafe",
          baseDir = new File(repo, "café"),
          versionFile = new File(repo, "café/version.sbt")
        )

        detectChanged(vcs, Seq(project), env.state).flatMap { changed =>
          readLogs(
            env,
            required = Seq(
              "cafe has only version/excluded file changes since cafe-v0.1.0, treating as unchanged"
            )
          ).map(_ => assert(changed.isEmpty))
        }
      }
    }
  }

  test(
    "detectChangedProjects - exclude non-ASCII child project directories from parent " +
      "project diffs"
  ) {
    repoResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "café"))
        sbt.IO.write(new File(repo, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
        sbt.IO.write(new File(repo, "café/version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
        sbt.IO.write(new File(repo, "café/Core.scala"), "object Core {}\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "config", "core.quotePath", "true")
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "root-v0.1.0")
        TestSupport.runGit(repo, "tag", "cafe-v0.1.0")

        sbt.IO.write(new File(repo, "café/Core.scala"), "object Core { val changed = true }\n")
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Update child sources")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        val root  = rootProject(repo)
        val child = projectInfo(
          repo,
          name = "cafe",
          baseDir = new File(repo, "café"),
          versionFile = new File(repo, "café/version.sbt")
        )

        detectChanged(vcs, Seq(root, child), env.state).flatMap { changed =>
          readLogs(
            env,
            required = Seq(
              "root has only version/excluded file changes since root-v0.1.0, treating as unchanged",
              "cafe has 1 changed file(s) since cafe-v0.1.0"
            )
          ).map(_ => assertEquals(changed.map(_.name), Seq("cafe")))
        }
      }
    }
  }

  test("detectChangedProjects - handle pathspec-magic-looking directory names literally") {
    // A directory named like git pathspec magic used to be passed as a diff pathspec and
    // made `git diff` fail (conservative over-detection). Diffs are now pathspec-free, so
    // such names are matched literally in Scala and detection works normally. The probe
    // below documents that a pathspec argument WOULD still fail.
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
          result  <- GitProcessSupport.runCommandResult(
                       repo,
                       Seq("diff", "--name-only", "magic-v0.1.0..HEAD", "--", ":(badmagic)")
                     )
          _        = assert(result.exitCode != 0)
          changed <- detectChanged(vcs, Seq(project), env.state)
          logs    <- readLogs(
                       env,
                       required = Seq("magic has 1 changed file(s) since magic-v0.1.0")
                     )
        } yield {
          assertEquals(changed.map(_.name), Seq("magic"))
          assert(!logs.contains("git diff failed"))
        }
      }
    }
  }

  test("detectChangedProjects - conservatively mark changed when the tag diff fails") {
    repoResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core"))
        sbt.IO.write(
          new File(repo, "core/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(new File(repo, "core/Core.scala"), "object Core {}\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "core-v0.1.0")

        sbt.IO.write(new File(repo, "core/Core.scala"), "object Core { val changed = true }\n")
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Update core sources")

        // Delete the tag commit's root tree object: `git describe` (a commit walk) still
        // succeeds, but `git diff <tag>..HEAD` needs the tree and fails — the only way to
        // hit the diff-failure branch now that no pathspec can make the diff itself fail.
        val treeHash   = TestSupport.runGit(repo, "rev-parse", "core-v0.1.0^{tree}").trim
        val objectFile =
          new File(repo, s".git/objects/${treeHash.take(2)}/${treeHash.drop(2)}")
        objectFile.setWritable(true)
        assert(objectFile.delete(), s"failed to delete loose object $objectFile")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        detectChanged(vcs, Seq(nestedProject(repo, "core")), env.state).flatMap { changed =>
          readLogs(
            env,
            required = Seq("git diff failed for core", "Conservatively treating as changed")
          ).map(_ => assertEquals(changed.map(_.name), Seq("core")))
        }
      }
    }
  }

  test("diffFilesSinceTag - return the git error detail when the tag does not exist") {
    repoResource.use { repo =>
      IO.blocking {
        sbt.IO.write(new File(repo, "file.txt"), "content\n")
        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        repo
      } *> detectVcs(repo).flatMap { vcs =>
        ChangeDetection.diffFilesSinceTag(vcs, "does-not-exist").map {
          case Left(detail) =>
            assert(
              detail.contains("git diff failed with exit code"),
              s"unexpected error detail: $detail"
            )
          case Right(files) =>
            fail(s"Expected a failed diff for a nonexistent tag, got: ${files.mkString(", ")}")
        }
      }
    }
  }

  test("detectChangedProjects - not attribute sibling-prefix directory changes to a project") {
    // The Scala scope filter must be boundary-aware like a git pathspec: "core-extra/x"
    // must not count as a change under project dir "core".
    repoResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core"))
        sbt.IO.createDirectory(new File(repo, "core-extra"))
        sbt.IO.write(
          new File(repo, "core/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(new File(repo, "core-extra/Extra.scala"), "object Extra {}\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "core-v0.1.0")

        sbt.IO.write(
          new File(repo, "core-extra/Extra.scala"),
          "object Extra { val changed = true }\n"
        )
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Update sibling sources")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        detectChanged(vcs, Seq(nestedProject(repo, "core")), env.state).flatMap { changed =>
          readLogs(env, required = Seq("core unchanged since core-v0.1.0")).map { _ =>
            assert(changed.isEmpty)
          }
        }
      }
    }
  }

  test("detectChangedProjects - count only in-scope files in the changed-files log") {
    // The full-repo diff sees changes outside the project's directory; the logged count
    // must stay scope-filtered.
    repoResource.use { repo =>
      IO.blocking {
        sbt.IO.createDirectory(new File(repo, "core"))
        sbt.IO.write(new File(repo, "README.md"), "# Readme\n")
        sbt.IO.write(
          new File(repo, "core/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
        sbt.IO.write(new File(repo, "core/Core.scala"), "object Core {}\n")

        TestSupport.initGitRepo(repo)
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        TestSupport.runGit(repo, "tag", "core-v0.1.0")

        sbt.IO.write(new File(repo, "README.md"), "# Readme updated\n")
        sbt.IO.write(new File(repo, "core/Core.scala"), "object Core { val changed = true }\n")
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Update root and core")

        repo
      }.flatMap { _ =>
        detectVcs(repo).map(vcs => (vcs, testEnv(repo)))
      }.flatMap { case (vcs, env) =>
        detectChanged(vcs, Seq(nestedProject(repo, "core")), env.state).flatMap { changed =>
          readLogs(
            env,
            required = Seq("core has 1 changed file(s) since core-v0.1.0")
          ).map { logs =>
            assertEquals(changed.map(_.name), Seq("core"))
            assert(!logs.contains("core has 2 changed file(s)"))
          }
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
