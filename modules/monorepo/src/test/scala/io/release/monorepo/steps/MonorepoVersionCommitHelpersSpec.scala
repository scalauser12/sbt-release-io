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
