package io.release.monorepo.internal.steps

import cats.effect.IO
import cats.effect.Resource
import io.release.TestSupport
import io.release.monorepo.MonorepoContext
import io.release.monorepo.internal.steps.*
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.Project

import java.io.File

class MonorepoVersionCommitHelpersSpec extends CatsEffectSuite {

  test("commitIfChanged - commits and returns context when tracked changes are staged") {
    gitRepoWithVcsResource.use { case (repo, vcs) =>
      val state = loadedState(repo)
      val ctx   = MonorepoContext(state = state, vcs = Some(vcs))

      for {
        _              <- IO.blocking {
                            sbt.IO.write(new File(repo, "changes.txt"), "new content")
                            TestSupport.runGit(repo, "add", "changes.txt")
                          }
        result         <- MonorepoVersionCommitHelpers.commitIfChanged(
                            ctx,
                            vcs,
                            msg = "chore: bump versions",
                            sign = false,
                            signOff = false
                          )
        logAfterCommit <- IO.blocking(TestSupport.runGit(repo, "log", "--oneline"))
      } yield {
        assertEquals(result, ctx)
        assert(logAfterCommit.contains("chore: bump versions"))
      }
    }
  }

  test("commitIfChanged - skips commit and returns context unchanged when working tree is clean") {
    gitRepoWithVcsResource.use { case (repo, vcs) =>
      val state = loadedState(repo)
      val ctx   = MonorepoContext(state = state, vcs = Some(vcs))

      for {
        commitsBefore <- IO.blocking(TestSupport.runGit(repo, "log", "--oneline"))
        result        <- MonorepoVersionCommitHelpers.commitIfChanged(
                           ctx,
                           vcs,
                           msg = "chore: should not appear",
                           sign = false,
                           signOff = false
                         )
        commitsAfter  <- IO.blocking(TestSupport.runGit(repo, "log", "--oneline"))
      } yield {
        assertEquals(result, ctx)
        assertEquals(commitsAfter.trim, commitsBefore.trim)
        assert(!commitsAfter.contains("chore: should not appear"))
      }
    }
  }

  test("commitIfChanged - skips commit when only untracked files are present") {
    gitRepoWithVcsResource.use { case (repo, vcs) =>
      IO.blocking(sbt.IO.write(new File(repo, "untracked.txt"), "not staged")).flatMap { _ =>
        val state = loadedState(repo)
        val ctx   = MonorepoContext(state = state, vcs = Some(vcs))

        for {
          commitsBefore <- IO.blocking(TestSupport.runGit(repo, "log", "--oneline"))
          result        <- MonorepoVersionCommitHelpers.commitIfChanged(
                             ctx,
                             vcs,
                             msg = "chore: should not appear",
                             sign = false,
                             signOff = false
                           )
          commitsAfter  <- IO.blocking(TestSupport.runGit(repo, "log", "--oneline"))
        } yield {
          assertEquals(result, ctx)
          assertEquals(commitsAfter.trim, commitsBefore.trim)
        }
      }
    }
  }

  test("commitIfChanged - skips commit when tracked files are modified but not staged") {
    gitRepoWithVcsResource.use { case (repo, vcs) =>
      IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "modified but not staged")).flatMap {
        _ =>
          val state = loadedState(repo)
          val ctx   = MonorepoContext(state = state, vcs = Some(vcs))

          for {
            commitsBefore <- IO.blocking(TestSupport.runGit(repo, "log", "--oneline"))
            result        <- MonorepoVersionCommitHelpers.commitIfChanged(
                               ctx,
                               vcs,
                               msg = "chore: should not appear",
                               sign = false,
                               signOff = false
                             )
            commitsAfter  <- IO.blocking(TestSupport.runGit(repo, "log", "--oneline"))
          } yield {
            assertEquals(result, ctx)
            assertEquals(commitsAfter.trim, commitsBefore.trim)
            assert(!commitsAfter.contains("chore: should not appear"))
          }
      }
    }
  }

  test("assertOnlyVersionFilesDirty - accept a dirty version file under a non-ASCII directory") {
    // Pins the `-z` fix for the commit guard: with line-oriented output git C-quoted the
    // path, so the version file itself was misread as an "unrelated tracked change" and the
    // release falsely aborted. core.quotePath is forced to true so the pre-fix failure
    // reproduces regardless of the developer's global git config.
    gitRepoWithVcsResource.use { case (repo, vcs) =>
      for {
        _ <- IO.blocking {
               TestSupport.runGit(repo, "config", "core.quotePath", "true")
               sbt.IO.write(new File(repo, "café/version.sbt"), "version := \"0.1.0\"\n")
               TestSupport.commitAll(repo, "Add version file")
               sbt.IO.write(new File(repo, "café/version.sbt"), "version := \"0.2.0\"\n")
             }
        _ <- MonorepoVersionCommitHelpers.assertOnlyVersionFilesDirty(
               Seq("café/version.sbt"),
               vcs
             )
      } yield ()
    }
  }

  test("assertOnlyVersionFilesDirty - still reject genuinely unrelated dirty files") {
    gitRepoWithVcsResource.use { case (repo, vcs) =>
      IO.blocking {
        TestSupport.runGit(repo, "config", "core.quotePath", "true")
        sbt.IO.write(new File(repo, "café/version.sbt"), "version := \"0.1.0\"\n")
        TestSupport.commitAll(repo, "Add version file")
        sbt.IO.write(new File(repo, "café/version.sbt"), "version := \"0.2.0\"\n")
        sbt.IO.write(new File(repo, "file.txt"), "unrelated change")
      } *>
        MonorepoVersionCommitHelpers
          .assertOnlyVersionFilesDirty(Seq("café/version.sbt"), vcs)
          .attempt
          .map {
            case Left(err: IllegalStateException) =>
              assert(
                err.getMessage.contains("unrelated tracked changes: file.txt"),
                s"expected file.txt as the unrelated change; got: ${err.getMessage}"
              )
            case Left(other)                      =>
              fail(
                s"Expected IllegalStateException, got ${other.getClass.getName}: ${other.getMessage}"
              )
            case Right(_)                         =>
              fail("Expected assertOnlyVersionFilesDirty to reject the unrelated dirty file")
          }
    }
  }

  private def gitRepoWithVcsResource: Resource[IO, (File, Vcs)] =
    TestSupport.tempDirResource("monorepo-vcs-commit-helpers-spec").evalMap { repo =>
      IO.blocking {
        TestSupport.initGitRepo(repo)
        sbt.IO.write(new File(repo, "file.txt"), "initial")
        TestSupport.commitAll(repo, "Initial commit")
        repo
      }.flatMap { initialized =>
        Vcs.detect(initialized).flatMap {
          case Some(vcs) => IO.pure((initialized, vcs))
          case None      =>
            IO.raiseError(
              new RuntimeException(s"Failed to detect VCS in ${initialized.getAbsolutePath}")
            )
        }
      }
    }

  private def loadedState(repo: File) =
    TestSupport.loadedState(
      repo,
      Seq(
        Project("root", repo).settings(
          io.release.ReleaseSharedKeys.releaseIOVcsIgnoreUntrackedFiles := true
        )
      ),
      currentProjectId = Some("root")
    )
}
