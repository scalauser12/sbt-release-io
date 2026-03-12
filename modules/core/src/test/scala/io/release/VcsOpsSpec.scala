package io.release

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Resource}
import org.specs2.mutable.Specification
import _root_.io.release.vcs.Vcs

import java.io.File
import java.nio.file.Files
class VcsOpsSpec extends Specification with CatsEffect {

  "VcsOps.detectVcsFromBase" should {

    "succeed for an initialized Git repo" in {
      gitRepoResource.use { repo =>
        VcsOps.detectVcsFromBase(repo).map { vcs =>
          vcs.commandName must_== "git"
        }
      }
    }

    "raise RuntimeException for a non-Git directory" in {
      tempDirResource.use { dir =>
        VcsOps.detectVcsFromBase(dir).attempt.map {
          case Left(e: RuntimeException) =>
            e.getMessage must contain("No VCS detected at")
          case other                     =>
            ko(s"Expected RuntimeException but got $other")
        }
      }
    }
  }

  "VcsOps.checkCleanFromVcs" should {

    "succeed on a clean repo and return the current hash" in {
      gitRepoWithCommitResource.use { case (repo, vcs) =>
        VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = false).map { result =>
          (result.currentHash must not(beEmpty[String]))
            .and(result.vcs.commandName must_== "git")
        }
      }
    }

    "raise error listing modified files when a tracked file is modified" in {
      gitRepoWithCommitResource.use { case (repo, vcs) =>
        IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "modified")) *>
          VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = false).attempt.map {
            case Left(e: RuntimeException) =>
              (e.getMessage must contain("unstaged modified files")) and
                (e.getMessage must contain("file.txt"))
            case other                     =>
              ko(s"Expected RuntimeException but got $other")
          }
      }
    }

    "raise error listing untracked files when untracked files exist" in {
      gitRepoWithCommitResource.use { case (repo, vcs) =>
        IO.blocking(sbt.IO.write(new File(repo, "untracked.txt"), "new")) *>
          VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = false).attempt.map {
            case Left(e: RuntimeException) =>
              (e.getMessage must contain("untracked files")) and
                (e.getMessage must contain("untracked.txt"))
            case other                     =>
              ko(s"Expected RuntimeException but got $other")
          }
      }
    }

    "succeed with untracked files when ignoreUntracked is true" in {
      gitRepoWithCommitResource.use { case (repo, vcs) =>
        IO.blocking(sbt.IO.write(new File(repo, "untracked.txt"), "new")) *>
          VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = true).map { result =>
            result.currentHash must not(beEmpty[String])
          }
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
