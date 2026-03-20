package io.release

import cats.effect.{IO, Resource}
import io.release.vcs.Vcs
import munit.CatsEffectSuite

import java.io.File
import java.nio.file.Files

class VcsOpsSpec extends CatsEffectSuite {

  test("detectVcsFromBase - succeed for an initialized Git repo") {
    gitRepoResource.use { repo =>
      VcsOps.detectVcsFromBase(repo).map { vcs =>
        assertEquals(vcs.commandName, "git")
      }
    }
  }

  test("detectVcsFromBase - raise RuntimeException for a non-Git directory") {
    tempDirResource.use { dir =>
      VcsOps.detectVcsFromBase(dir).attempt.map {
        case Left(e: RuntimeException) =>
          assert(e.getMessage.contains("No VCS detected at"))
        case other                     =>
          fail(s"Expected RuntimeException but got $other")
      }
    }
  }

  test("checkCleanFromVcs - succeed on a clean repo and return the current hash") {
    gitRepoWithCommitResource.use { case (repo, vcs) =>
      VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = false).map { result =>
        assert(result.currentHash.nonEmpty)
        assertEquals(result.vcs.commandName, "git")
      }
    }
  }

  test("checkCleanFromVcs - raise error listing modified files when a tracked file is modified") {
    gitRepoWithCommitResource.use { case (repo, vcs) =>
      IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "modified")) *>
        VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = false).attempt.map {
          case Left(e: RuntimeException) =>
            assert(e.getMessage.contains("unstaged modified files"))
            assert(e.getMessage.contains("file.txt"))
          case other                     =>
            fail(s"Expected RuntimeException but got $other")
        }
    }
  }

  test("checkCleanFromVcs - raise error listing staged files when staged-but-uncommitted") {
    gitRepoWithCommitResource.use { case (repo, vcs) =>
      IO.blocking {
        sbt.IO.write(new File(repo, "staged.txt"), "staged content")
        TestSupport.runGit(repo, "add", "staged.txt")
      } *>
        VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = false).attempt.map {
          case Left(e: RuntimeException) =>
            assert(e.getMessage.contains("staged uncommitted changes"))
            assert(e.getMessage.contains("staged.txt"))
          case other                     =>
            fail(s"Expected RuntimeException but got $other")
        }
    }
  }

  test("checkCleanFromVcs - raise error listing untracked files when untracked files exist") {
    gitRepoWithCommitResource.use { case (repo, vcs) =>
      IO.blocking(sbt.IO.write(new File(repo, "untracked.txt"), "new")) *>
        VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = false).attempt.map {
          case Left(e: RuntimeException) =>
            assert(e.getMessage.contains("untracked files"))
            assert(e.getMessage.contains("untracked.txt"))
          case other                     =>
            fail(s"Expected RuntimeException but got $other")
        }
    }
  }

  test("checkCleanFromVcs - succeed with untracked files when ignoreUntracked is true") {
    gitRepoWithCommitResource.use { case (repo, vcs) =>
      IO.blocking(sbt.IO.write(new File(repo, "untracked.txt"), "new")) *>
        VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = true).map { result =>
          assert(result.currentHash.nonEmpty)
        }
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────

  private val tempDirResource: Resource[IO, File] =
    Resource.make(IO.blocking(Files.createTempDirectory("vcs-ops-spec").toFile))(dir =>
      IO.blocking(TestSupport.deleteRecursively(dir))
    )

  private val gitRepoResource: Resource[IO, File] =
    tempDirResource.evalMap { dir =>
      IO.blocking {
        TestSupport.initGitRepo(dir)
        dir
      }
    }

  private val gitRepoWithCommitResource: Resource[IO, (File, Vcs)] =
    gitRepoResource.evalMap { repo =>
      IO.blocking {
        sbt.IO.write(new File(repo, "file.txt"), "initial")
        TestSupport.runGit(repo, "add", ".")
        TestSupport.runGit(repo, "commit", "-m", "Initial commit")
        repo
      }.flatMap { r =>
        Vcs.detect(r).flatMap {
          case Some(vcs) => IO.pure((r, vcs))
          case None      =>
            IO.raiseError(
              new RuntimeException(s"Failed to detect VCS in ${r.getAbsolutePath}")
            )
        }
      }
    }
}
