package io.release

import cats.effect.{IO, Ref, Resource}
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.{Project, State}

import java.io.File

class VcsOpsSpec extends CatsEffectSuite {

  test("detectVcsFromBase - succeed for an initialized Git repo") {
    gitRepoResource.use { repo =>
      VcsOps.detectVcsFromBase(repo).map { vcs =>
        assertEquals(vcs.commandName, "git")
      }
    }
  }

  test("detectVcsFromBase - raise IllegalStateException for a non-Git directory") {
    tempDirResource.use { dir =>
      VcsOps.detectVcsFromBase(dir).attempt.map {
        case Left(e: IllegalStateException) =>
          assert(e.getMessage.contains("No VCS detected at"))
        case other                        =>
          fail(s"Expected IllegalStateException but got $other")
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
          case Left(e: IllegalStateException) =>
            assert(e.getMessage.contains("unstaged modified files"))
            assert(e.getMessage.contains("file.txt"))
          case other                        =>
            fail(s"Expected IllegalStateException but got $other")
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
          case Left(e: IllegalStateException) =>
            assert(e.getMessage.contains("staged uncommitted changes"))
            assert(e.getMessage.contains("staged.txt"))
          case other                        =>
            fail(s"Expected IllegalStateException but got $other")
        }
    }
  }

  test("checkCleanFromVcs - raise error listing untracked files when untracked files exist") {
    gitRepoWithCommitResource.use { case (repo, vcs) =>
      IO.blocking(sbt.IO.write(new File(repo, "untracked.txt"), "new")) *>
        VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = false).attempt.map {
          case Left(e: IllegalStateException) =>
            assert(e.getMessage.contains("untracked files"))
            assert(e.getMessage.contains("untracked.txt"))
          case other                        =>
            fail(s"Expected IllegalStateException but got $other")
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

  test("detectVcs - detect Git from a loaded sbt state") {
    gitRepoWithCommitResource.use { case (repo, _) =>
      IO(sessionState(repo, ignoreUntracked = true)).flatMap { state =>
        VcsOps.detectVcs(state).map { vcs =>
          assertEquals(vcs.commandName, "git")
        }
      }
    }
  }

  test("checkCleanWorkingDir(state) - succeed for a clean loaded repo") {
    gitRepoWithCommitResource.use { case (repo, _) =>
      IO(sessionState(repo, ignoreUntracked = true)).flatMap { state =>
        VcsOps.checkCleanWorkingDir(state).map { result =>
          assert(result.currentHash.nonEmpty)
          assertEquals(result.vcs.commandName, "git")
        }
      }
    }
  }

  test("checkCleanWorkingDir(state, vcs) - read settings from the loaded state") {
    gitRepoWithCommitResource.use { case (repo, _) =>
      IO(sessionState(repo, ignoreUntracked = true)).flatMap { state =>
        for {
          vcs    <- VcsOps.detectVcs(state)
          result <- VcsOps.checkCleanWorkingDir(state, vcs)
        } yield {
          assert(result.currentHash.nonEmpty)
        }
      }
    }
  }

  test("relativizeToBase - return the path relative to the VCS root") {
    gitRepoWithCommitResource.use { case (repo, vcs) =>
      IO.blocking {
        val nested = new File(repo, "nested/version.sbt")
        sbt.IO.write(nested, """version := "0.1.0-SNAPSHOT"""")
        nested
      }.flatMap { file =>
        VcsOps.relativizeToBase(vcs, file).map { relativePath =>
          assertEquals(relativePath, "nested/version.sbt")
        }
      }
    }
  }

  test("relativizeToBase - raise when the file is outside the VCS root") {
    tempDirResource.use { outside =>
      gitRepoWithCommitResource.use { case (_, vcs) =>
        val external = new File(outside, "version.sbt")
        IO.blocking(sbt.IO.write(external, """version := "0.1.0-SNAPSHOT"""")) *>
          VcsOps.relativizeToBase(vcs, external).attempt.map {
            case Left(err: IllegalStateException) =>
              assert(err.getMessage.contains("outside of VCS root"))
            case other                            =>
              fail(s"Expected IllegalStateException but got $other")
          }
      }
    }
  }

  test("trackedStatus - drop untracked lines from porcelain status") {
    tempDirResource.use { dir =>
      val vcs = new StubVcs(
        dir,
        status0 = IO.pure(" M tracked.txt\n?? untracked.txt\nA  staged.txt")
      )

      VcsOps.trackedStatus(vcs).map { status =>
        assertEquals(status, " M tracked.txt\nA  staged.txt")
      }
    }
  }

  test("validatePushRemote - succeed when the tracking remote is reachable") {
    tempDirResource.use { dir =>
      VcsOps
        .validatePushRemote(
          TestSupport.dummyState(dir),
          interactive = false,
          useDefaults = false,
          new StubVcs(dir, checkRemote0 = IO.pure(0))
        )
    }
  }

  test("validatePushRemote - abort in non-interactive mode when the remote check fails") {
    tempDirResource.use { dir =>
      VcsOps
        .validatePushRemote(
          TestSupport.dummyState(dir),
          interactive = false,
          useDefaults = false,
          new StubVcs(dir, checkRemote0 = IO.pure(1))
        )
        .attempt
        .map {
          case Left(err: IllegalStateException) =>
            assertEquals(err.getMessage, "Aborting the release due to remote check failure.")
          case other                            =>
            fail(s"Expected IllegalStateException but got $other")
        }
    }
  }

  test("validatePushReadiness - fail when no tracking branch is configured") {
    tempDirResource.use { dir =>
      VcsOps
        .validatePushReadiness(
          TestSupport.dummyState(dir),
          interactive = false,
          useDefaults = false,
          new StubVcs(dir, hasUpstream0 = IO.pure(false), currentBranch0 = IO.pure("feature"))
        )
        .attempt
        .map {
          case Left(err: IllegalStateException) =>
            assert(err.getMessage.contains("No tracking branch configured for 'feature'"))
          case other                            =>
            fail(s"Expected IllegalStateException but got $other")
        }
    }
  }

  test("validatePushReadiness - abort in non-interactive mode when local is behind remote") {
    tempDirResource.use { dir =>
      VcsOps
        .validatePushReadiness(
          TestSupport.dummyState(dir),
          interactive = false,
          useDefaults = false,
          new StubVcs(dir, isBehindRemote0 = IO.pure(true))
        )
        .attempt
        .map {
          case Left(err: IllegalStateException) =>
            assertEquals(err.getMessage, "Merge the upstream commits and run release again.")
          case other                            =>
            fail(s"Expected IllegalStateException but got $other")
        }
    }
  }

  test("validatePushReadiness - ignore isBehindRemote errors and continue") {
    tempDirResource.use { dir =>
      VcsOps
        .validatePushReadiness(
          TestSupport.dummyState(dir),
          interactive = false,
          useDefaults = false,
          new StubVcs(dir, isBehindRemote0 = IO.raiseError(new RuntimeException("boom")))
        )
    }
  }

  test("interactivePushAfterRemote - non-interactive runs doPush") {
    tempDirResource.use { dir =>
      for {
        pushed     <- Ref[IO].of(false)
        declined   <- Ref[IO].of(false)
        vcs         = new StubVcs(dir)
        state       = TestSupport.dummyState(dir)
        _          <- VcsOps.interactivePushAfterRemote(
                        state,
                        interactive = false,
                        useDefaults = false,
                        vcs,
                        remoteCheckLog = None
                      )(
                        doPush = pushed.set(true),
                        onDeclinePush = declined.set(true)
                      )
        didPush    <- pushed.get
        didDecline <- declined.get
      } yield {
        assertEquals(didPush, true)
        assertEquals(didDecline, false)
      }
    }
  }

  test("interactivePushAfterRemote - interactive with useDefaults runs doPush") {
    tempDirResource.use { dir =>
      val state = TestSupport.dummyState(dir)
      for {
        pushed     <- Ref[IO].of(false)
        declined   <- Ref[IO].of(false)
        vcs         = new StubVcs(dir)
        _          <- VcsOps.interactivePushAfterRemote(
                        state,
                        interactive = true,
                        useDefaults = true,
                        vcs,
                        remoteCheckLog = None
                      )(
                        doPush = pushed.set(true),
                        onDeclinePush = declined.set(true)
                      )
        didPush    <- pushed.get
        didDecline <- declined.get
      } yield {
        assertEquals(didPush, true)
        assertEquals(didDecline, false)
      }
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────

  private val tempDirResource: Resource[IO, File] =
    TestSupport.tempDirResource("vcs-ops-spec")

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
        TestSupport.commitAll(repo, "Initial commit")
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

  private def sessionState(repo: File, ignoreUntracked: Boolean): State =
    TestSupport.appendSessionSettings(
      TestSupport.loadedState(
        repo,
        Seq(Project("root", repo)),
        currentProjectId = Some("root")
      ),
      Seq(ReleaseIO.releaseIOIgnoreUntrackedFiles := ignoreUntracked)
    )
}

private final class StubVcs(
    override val baseDir: File,
    currentHash0: IO[String] = IO.pure("abc"),
    currentBranch0: IO[String] = IO.pure("main"),
    trackingRemote0: IO[String] = IO.pure("origin"),
    hasUpstream0: IO[Boolean] = IO.pure(true),
    isBehindRemote0: IO[Boolean] = IO.pure(false),
    existsTag0: IO[Boolean] = IO.pure(false),
    modifiedFiles0: IO[Seq[String]] = IO.pure(Nil),
    stagedFiles0: IO[Seq[String]] = IO.pure(Nil),
    untrackedFiles0: IO[Seq[String]] = IO.pure(Nil),
    status0: IO[String] = IO.pure(""),
    checkRemote0: IO[Int] = IO.pure(0)
) extends Vcs {
  override def commandName: String = "git"

  override def currentHash: IO[String]              = currentHash0
  override def currentBranch: IO[String]            = currentBranch0
  override def trackingRemote: IO[String]           = trackingRemote0
  override def hasUpstream: IO[Boolean]             = hasUpstream0
  override def isBehindRemote: IO[Boolean]          = isBehindRemote0
  override def existsTag(name: String): IO[Boolean] = existsTag0
  override def modifiedFiles: IO[Seq[String]]       = modifiedFiles0
  override def stagedFiles: IO[Seq[String]]         = stagedFiles0
  override def untrackedFiles: IO[Seq[String]]      = untrackedFiles0
  override def status: IO[String]                   = status0
  override def checkRemote(remote: String): IO[Int] = checkRemote0

  override def add(files: String*): IO[Unit] = IO.unit

  override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit] = IO.unit

  override def tag(name: String, comment: String, sign: Boolean, force: Boolean): IO[Unit] =
    IO.unit

  override def pushChanges: IO[Unit] = IO.unit
}
