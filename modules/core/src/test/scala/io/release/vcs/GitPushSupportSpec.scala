package io.release.vcs

import cats.effect.IO
import cats.effect.Resource
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import munit.CatsEffectSuite

import java.io.File

class GitPushSupportSpec extends CatsEffectSuite {
  private val fixturePrefix = "git-push-support-spec"

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

  test("pushTrackedBranch - push the branch when a local tag shares the branch name") {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-branch-tag-collision").use {
      case (repo, remoteRepo) =>
        for {
          _          <- IO.blocking {
                          TestSupport.runGit(repo, "tag", "main")
                          TestSupport.runGit(repo, "commit", "--allow-empty", "-m", "post-tag commit")
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

  test("pushTrackedBranchWithTags - push only the branch when no tags are recorded") {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-with-tags-empty").use {
      case (repo, remoteRepo) =>
        for {
          _          <- IO.blocking {
                          TestSupport.runGit(repo, "commit", "--allow-empty", "-m", "advance")
                        }
          target     <- GitPushSupport.resolvePushTarget(new Git(repo))
          _          <- GitPushSupport.pushTrackedBranchWithTags(new Git(repo), target, Seq.empty)
          localHead  <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
          remoteHead <-
            IO.blocking(
              TestSupport.runGit(remoteRepo, "rev-parse", "--verify", "refs/heads/main").trim
            )
          remoteTags <-
            IO.blocking(TestSupport.runGit(remoteRepo, "tag", "--list").trim)
        } yield {
          assertEquals(remoteHead, localHead)
          assertEquals(remoteTags, "")
        }
    }
  }

  test("pushTrackedBranchWithTags - push the branch and recorded tag together") {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-with-tags-happy").use {
      case (repo, remoteRepo) =>
        for {
          _          <- IO.blocking {
                          TestSupport.runGit(repo, "commit", "--allow-empty", "-m", "advance")
                          TestSupport.runGit(repo, "tag", "-a", "v1.0.0", "-m", "release v1.0.0")
                        }
          target     <- GitPushSupport.resolvePushTarget(new Git(repo))
          _          <- GitPushSupport.pushTrackedBranchWithTags(
                          new Git(repo),
                          target,
                          Seq("v1.0.0")
                        )
          localHead  <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
          remoteHead <-
            IO.blocking(
              TestSupport.runGit(remoteRepo, "rev-parse", "--verify", "refs/heads/main").trim
            )
          remoteTag  <-
            IO.blocking(
              TestSupport
                .runGit(remoteRepo, "rev-parse", "--verify", "refs/tags/v1.0.0^{commit}")
                .trim
            )
        } yield {
          assertEquals(remoteHead, localHead)
          assertEquals(remoteTag, localHead)
        }
    }
  }

  test(
    "pushTrackedBranchWithTags - succeed when the recorded tag already exists at the same commit"
  ) {
    Resource
      .both(
        TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-with-tags-same-hash"),
        TestSupport.tempDirResource(s"$fixturePrefix-with-tags-same-hash-clone")
      )
      .use { case ((repo, remoteRepo), cloneDir) =>
        for {
          _          <- IO.blocking {
                          // Pre-seed the remote with the tag via a sidecar clone, then fetch
                          // the same tag object back into the main repo. The atomic push then
                          // sees an identical local/remote tag object → no-op tag update.
                          TestSupport.runGit(cloneDir, "clone", remoteRepo.getAbsolutePath, ".")
                          TestSupport.runGit(cloneDir, "config", "user.email", "test@example.com")
                          TestSupport.runGit(cloneDir, "config", "user.name", "Test User")
                          TestSupport.runGit(cloneDir, "tag", "-a", "v1.0.0", "-m", "release")
                          TestSupport.runGit(cloneDir, "push", "origin", "v1.0.0")
                          TestSupport.runGit(repo, "fetch", "origin", "tag", "v1.0.0")
                        }
          target     <- GitPushSupport.resolvePushTarget(new Git(repo))
          _          <- GitPushSupport.pushTrackedBranchWithTags(
                          new Git(repo),
                          target,
                          Seq("v1.0.0")
                        )
          localHead  <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
          remoteHead <-
            IO.blocking(
              TestSupport.runGit(remoteRepo, "rev-parse", "--verify", "refs/heads/main").trim
            )
          remoteTag  <-
            IO.blocking(
              TestSupport
                .runGit(remoteRepo, "rev-parse", "--verify", "refs/tags/v1.0.0^{commit}")
                .trim
            )
        } yield {
          assertEquals(remoteHead, localHead)
          assertEquals(remoteTag, localHead)
        }
      }
  }

  test("pushTrackedBranchWithTags - reject empty or blank tag names before invoking git") {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-with-tags-blank").use {
      case (repo, _) =>
        val target   = GitPushSupport.GitPushTarget(
          remote = "origin",
          localBranch = "main",
          upstreamBranch = "main"
        )
        val expected = "Tag name cannot be empty when pushing to the remote."
        for {
          _ <- assertFailure[IllegalStateException, Unit](
                 GitPushSupport.pushTrackedBranchWithTags(new Git(repo), target, Seq("   "))
               )(err => assertEquals(err.getMessage, expected))
          _ <- assertFailure[IllegalStateException, Unit](
                 GitPushSupport.pushTrackedBranchWithTags(
                   new Git(repo),
                   target,
                   Seq("v1.0.0", "")
                 )
               )(err => assertEquals(err.getMessage, expected))
        } yield ()
    }
  }

  test(
    "pushTrackedBranchWithTags - leave the remote branch unchanged when a tag conflict aborts the atomic push"
  ) {
    Resource
      .both(
        TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-with-tags-atomic-rollback"),
        TestSupport.tempDirResource(s"$fixturePrefix-with-tags-atomic-rollback-clone")
      )
      .use { case ((repo, remoteRepo), cloneDir) =>
        for {
          _                <- IO.blocking {
                                // Sidecar clone advances HEAD to commit B and pushes
                                // refs/tags/v1.0.0 → B to the remote. Remote branch stays at A
                                // because we only push the tag.
                                TestSupport.runGit(cloneDir, "clone", remoteRepo.getAbsolutePath, ".")
                                TestSupport.runGit(cloneDir, "config", "user.email", "test@example.com")
                                TestSupport.runGit(cloneDir, "config", "user.name", "Test User")
                                TestSupport.runGit(cloneDir, "commit", "--allow-empty", "-m", "sidecar B")
                                TestSupport.runGit(cloneDir, "tag", "-a", "v1.0.0", "-m", "release at B")
                                TestSupport.runGit(cloneDir, "push", "origin", "v1.0.0")
                                // Main repo advances HEAD to commit C and tags v1.0.0 at C.
                                TestSupport.runGit(repo, "commit", "--allow-empty", "-m", "main C")
                                TestSupport.runGit(repo, "tag", "-a", "v1.0.0", "-m", "release at C")
                              }
          target           <- GitPushSupport.resolvePushTarget(new Git(repo))
          remoteBranchPre  <-
            IO.blocking(
              TestSupport.runGit(remoteRepo, "rev-parse", "--verify", "refs/heads/main").trim
            )
          _                <- assertFailure[IllegalStateException, Unit](
                                GitPushSupport.pushTrackedBranchWithTags(
                                  new Git(repo),
                                  target,
                                  Seq("v1.0.0")
                                )
                              )(_ => ())
          remoteBranchPost <-
            IO.blocking(
              TestSupport.runGit(remoteRepo, "rev-parse", "--verify", "refs/heads/main").trim
            )
        } yield assertEquals(
          remoteBranchPost,
          remoteBranchPre,
          "atomic push must roll back the branch update when the tag conflicts"
        )
      }
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
