package io.release.vcs

import cats.effect.IO
import cats.effect.Resource
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import munit.CatsEffectSuite

import java.io.File

class GitPushSupportSpec extends CatsEffectSuite {
  private val fixturePrefix = "git-push-support-spec"

  test("pushTag - reject empty or blank tag names before invoking git") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-empty-tag").use { repo =>
      val vcs      = new Git(repo)
      val expected = "Tag name cannot be empty when pushing to the remote."

      assertPushTagRejects(vcs, "origin", "", expected) *>
        assertPushTagRejects(vcs, "origin", "   ", expected)
    }
  }

  test("pushTag - reject empty or blank remote before invoking git") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-empty-remote").use { repo =>
      val vcs      = new Git(repo)
      val expected = "Remote name cannot be empty when pushing a tag."

      assertPushTagRejects(vcs, "", "v1.0.0", expected) *>
        assertPushTagRejects(vcs, "   ", "v1.0.0", expected)
    }
  }

  test("pushTag - push the tag when a local branch has the same name") {
    TestSupport.gitRepoWithBareRemoteResource(fixturePrefix).use { case (repo, remoteRepo) =>
      val tagName = "release-v1.0.0"

      Vcs.detect(repo).flatMap {
        case Some(vcs) =>
          for {
            _         <- IO.blocking {
                           TestSupport.runGit(repo, "branch", tagName)
                           TestSupport.runGit(repo, "tag", tagName)
                         }
            _         <- GitPushSupport.pushTag(vcs, "origin", tagName)
            remoteTag <- IO.blocking(TestSupport.runGit(remoteRepo, "tag", "--list", tagName).trim)
          } yield {
            assertEquals(remoteTag, tagName)
          }
        case None      =>
          IO.raiseError(new RuntimeException(s"Failed to detect VCS in ${repo.getAbsolutePath}"))
      }
    }
  }

  test("pushTag - trim surrounding whitespace from tag names before building the refspec") {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-trim-tag").use {
      case (repo, remoteRepo) =>
        val tagName = "release-trim-v1.0.0"

        for {
          _         <- IO.blocking(TestSupport.runGit(repo, "tag", tagName))
          _         <- GitPushSupport.pushTag(new Git(repo), "origin", s"  $tagName  ")
          remoteTag <- IO.blocking(TestSupport.runGit(remoteRepo, "tag", "--list", tagName).trim)
        } yield assertEquals(remoteTag, tagName)
    }
  }

  test("resolvePushTarget - reject when the configured tracking remote is blank") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-blank-remote").use { repo =>
      configureTracking(repo, branch = "main", remote = "   ", merge = "refs/heads/main") *>
        assertResolveFails(repo) { err =>
          assert(err.getMessage.contains("Tracking remote for branch 'main' is empty"))
          assert(err.getMessage.contains("branch.main.remote"))
        }
    }
  }

  test(
    "resolvePushTarget - reject '.' remotes even when surrounded by whitespace in git config"
  ) {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-padded-local-dot").use { repo =>
      configureTracking(repo, branch = "main", remote = " . ", merge = "refs/heads/main") *>
        assertResolveFails(repo) { err =>
          assert(err.getMessage.contains("tracks a local branch"))
          assert(err.getMessage.contains("branch.main.remote = '.'"))
        }
    }
  }

  test("resolvePushTarget - reject merge refs that do not start with refs/heads/") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-bad-merge-ref").use { repo =>
      configureTracking(repo, branch = "main", remote = "origin", merge = "refs/tags/v1.0.0") *>
        assertResolveFails(repo) { err =>
          assert(err.getMessage.contains("refs/tags/v1.0.0"))
          assert(err.getMessage.contains("must use the 'refs/heads/' format"))
        }
    }
  }

  test("resolvePushTarget - reject branches that track a local branch via '.' as their remote") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-local-dot-remote").use { repo =>
      IO.blocking {
        TestSupport.runGit(repo, "branch", "-M", "main")
        TestSupport.runGit(repo, "checkout", "-b", "feature")
        TestSupport.runGit(repo, "config", "branch.feature.remote", ".")
        TestSupport.runGit(repo, "config", "branch.feature.merge", "refs/heads/main")
      } *>
        assertResolveFails(repo) { err =>
          assert(err.getMessage.contains("tracks a local branch"))
          assert(err.getMessage.contains("branch.feature.remote = '.'"))
        }
    }
  }

  test("resolvePushTarget - raise a structured error when branch.X.remote is unset") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-missing-remote").use { repo =>
      IO.blocking(TestSupport.runGit(repo, "branch", "-M", "main")) *>
        assertResolveFails(repo) { err =>
          assert(err.getMessage.contains("no configured tracking remote"))
          assert(err.getMessage.contains("branch.main.remote"))
        }
    }
  }

  test("resolvePushTarget - raise a structured error when branch.X.merge is unset") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-missing-merge").use { repo =>
      IO.blocking {
        TestSupport.runGit(repo, "branch", "-M", "main")
        TestSupport.runGit(repo, "config", "branch.main.remote", "origin")
      } *>
        assertResolveFails(repo) { err =>
          assert(err.getMessage.contains("no configured upstream branch"))
          assert(err.getMessage.contains("branch.main.merge"))
        }
    }
  }

  test("pushTrackedBranch - create the configured tracking branch on the first push") {
    Resource
      .both(
        TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-first-push"),
        TestSupport.tempDirResource(s"$fixturePrefix-first-push-remote")
      )
      .use { case (repo, remoteRepo) =>
        for {
          _          <- IO.blocking {
                          TestSupport.runGit(repo, "branch", "-M", "main")
                          TestSupport.runGit(repo, "init", "--bare", remoteRepo.getAbsolutePath)
                          TestSupport.runGit(repo, "remote", "add", "origin", remoteRepo.getAbsolutePath)
                          TestSupport.runGit(repo, "config", "branch.main.remote", "origin")
                          TestSupport.runGit(repo, "config", "branch.main.merge", "refs/heads/main")
                        }
          _          <- GitPushSupport.pushTrackedBranch(new Git(repo), followTags = false)
          localHead  <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
          remoteHead <-
            IO.blocking(
              TestSupport.runGit(remoteRepo, "rev-parse", "--verify", "refs/heads/main").trim
            )
        } yield assertEquals(remoteHead, localHead)
      }
  }

  private def assertPushTagRejects(
      vcs: Vcs,
      remote: String,
      tag: String,
      expectedMessage: String
  ): IO[Unit] =
    assertFailure[IllegalStateException, Unit](GitPushSupport.pushTag(vcs, remote, tag)) { err =>
      assertEquals(err.getMessage, expectedMessage)
    }

  private def configureTracking(
      repo: File,
      branch: String,
      remote: String,
      merge: String
  ): IO[Unit] =
    IO.blocking {
      TestSupport.runGit(repo, "branch", "-M", branch)
      TestSupport.runGit(repo, "config", s"branch.$branch.remote", remote)
      TestSupport.runGit(repo, "config", s"branch.$branch.merge", merge)
      ()
    }

  private def assertResolveFails(repo: File)(check: IllegalStateException => Unit): IO[Unit] =
    assertFailure[IllegalStateException, GitPushSupport.GitPushTarget](
      GitPushSupport.resolvePushTarget(new Git(repo))
    )(check)
}
