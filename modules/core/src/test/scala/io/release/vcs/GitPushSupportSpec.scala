package io.release.vcs

import cats.effect.IO
import cats.effect.Resource
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import munit.CatsEffectSuite

import java.io.File
import scala.concurrent.duration.FiniteDuration

class GitPushSupportSpec extends CatsEffectSuite {
  private val fixturePrefix = "git-push-support-spec"

  test("pushTag - reject empty or blank tag names before invoking git") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val vcs = stubVcs(dir)

      assertFailure[IllegalStateException, Unit](GitPushSupport.pushTag(vcs, "origin", ""))(err =>
        assertEquals(err.getMessage, "Tag name cannot be empty when pushing to the remote.")
      ) *>
        assertFailure[IllegalStateException, Unit](GitPushSupport.pushTag(vcs, "origin", "   "))(
          err =>
            assertEquals(
              err.getMessage,
              "Tag name cannot be empty when pushing to the remote."
            )
        )
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

  test("resolvePushTarget - reject when the configured tracking remote is blank") {
    TestSupport.tempDirResource(s"$fixturePrefix-blank-remote").use { dir =>
      val vcs = stubVcs(dir, trackingRemoteOverride = IO.pure("   "))

      assertFailure[IllegalStateException, GitPushSupport.GitPushTarget](
        GitPushSupport.resolvePushTarget(vcs)
      ) { err =>
        assert(err.getMessage.contains("Tracking remote for branch 'main' is empty"))
        assert(err.getMessage.contains("branch.main.remote"))
      }
    }
  }

  test(
    "resolvePushTarget - reject '.' remotes even when surrounded by whitespace in git config"
  ) {
    TestSupport.tempDirResource(s"$fixturePrefix-padded-local-dot").use { dir =>
      val vcs = stubVcs(dir, trackingRemoteOverride = IO.pure(" . "))

      assertFailure[IllegalStateException, GitPushSupport.GitPushTarget](
        GitPushSupport.resolvePushTarget(vcs)
      ) { err =>
        assert(err.getMessage.contains("tracks a local branch"))
        assert(err.getMessage.contains("branch.main.remote = '.'"))
      }
    }
  }

  test("resolvePushTarget - reject merge refs that do not start with refs/heads/") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-bad-merge-ref").use { repo =>
      IO.blocking {
        TestSupport.runGit(repo, "branch", "-M", "main")
        TestSupport.runGit(repo, "config", "branch.main.remote", "origin")
        TestSupport.runGit(repo, "config", "branch.main.merge", "refs/tags/v1.0.0")
      } *>
        assertFailure[IllegalStateException, GitPushSupport.GitPushTarget](
          GitPushSupport.resolvePushTarget(new Git(repo))
        ) { err =>
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
        assertFailure[IllegalStateException, GitPushSupport.GitPushTarget](
          GitPushSupport.resolvePushTarget(new Git(repo))
        ) { err =>
          assert(err.getMessage.contains("tracks a local branch"))
          assert(err.getMessage.contains("branch.feature.remote = '.'"))
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

  private def stubVcs(
      dir: File,
      trackingRemoteOverride: IO[String] = IO.pure("origin")
  ): Vcs =
    new Vcs {
      override def commandName: String = "git"

      override def baseDir: File = dir

      override def currentHash: IO[String] = IO.pure("deadbeef")

      override def currentBranch: IO[String] = IO.pure("main")

      override def trackingRemote: IO[String] = trackingRemoteOverride

      override def upstreamTrackingHash: IO[Option[String]] = IO.pure(Some("origin/main"))

      override def hasUpstream: IO[Boolean] = IO.pure(true)

      override def isBehindRemote: IO[Boolean] = IO.pure(false)

      override def existsTag(name: String): IO[Boolean] = IO.pure(false)

      override def tagCommitHash(name: String): IO[Option[String]] = IO.pure(None)

      override def modifiedFiles: IO[Seq[String]] = IO.pure(Nil)

      override def stagedFiles: IO[Seq[String]] = IO.pure(Nil)

      override def untrackedFiles: IO[Seq[String]] = IO.pure(Nil)

      override def status: IO[String] = IO.pure("")

      override def checkRemote(remote: String): IO[Int] = IO.pure(0)

      override def checkRemoteWithTimeout(
          remote: String,
          timeout: FiniteDuration
      ): IO[Option[Int]] =
        checkRemote(remote).map(Some(_))

      override def add(files: String*): IO[Unit] = IO.unit

      override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit] =
        IO.unit

      override def tag(name: String, comment: String, sign: Boolean, force: Boolean): IO[Unit] =
        IO.unit

      override def pushChanges: IO[Unit] = IO.unit
    }
}
