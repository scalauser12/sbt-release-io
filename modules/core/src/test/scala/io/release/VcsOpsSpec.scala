package io.release

import cats.effect.IO
import cats.effect.Ref
import io.release.TestAssertions.assertFailure
import io.release.TestAssertions.assertIllegalStateMessage
import io.release.internal.CoreExecutionState
import io.release.internal.CoreReleasePlan
import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.ReleaseDecisionDefaults
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.Project
import sbt.Setting
import sbt.State
import sbt.internal.util.AttributeMap
import sbt.internal.util.ConsoleOut
import sbt.internal.util.GlobalLogging
import sbt.internal.util.MainAppender

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import scala.concurrent.duration.*

class VcsOpsSpec extends CatsEffectSuite {
  private val fixturePrefix = "vcs-ops-spec"

  test("detectVcsFromBase - succeed for an initialized Git repo") {
    TestSupport.gitRepoResource(fixturePrefix).use { repo =>
      VcsOps.detectVcsFromBase(repo).map { vcs =>
        assertEquals(vcs.commandName, "git")
      }
    }
  }

  test("detectVcsFromBase - raise IllegalStateException for a non-Git directory") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      assertFailure[IllegalStateException, Vcs](VcsOps.detectVcsFromBase(dir))(err =>
        assert(err.getMessage.contains("No VCS detected at"))
      )
    }
  }

  test("checkCleanFromVcs - succeed on a clean repo and return the current hash") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = false).map { result =>
        assert(result.currentHash.nonEmpty)
        assertEquals(result.vcs.commandName, "git")
      }
    }
  }

  test("checkCleanFromVcs - raise error listing modified files when a tracked file is modified") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "modified")) *>
        assertFailure[IllegalStateException, VcsOps.CleanCheckResult](
          VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = false)
        ) { err =>
          assert(err.getMessage.contains("unstaged modified files"))
          assert(err.getMessage.contains("file.txt"))
        }
    }
  }

  test("checkCleanFromVcs - raise error listing staged files when staged-but-uncommitted") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      IO.blocking {
        sbt.IO.write(new File(repo, "staged.txt"), "staged content")
        TestSupport.runGit(repo, "add", "staged.txt")
      } *>
        assertFailure[IllegalStateException, VcsOps.CleanCheckResult](
          VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = false)
        ) { err =>
          assert(err.getMessage.contains("staged uncommitted changes"))
          assert(err.getMessage.contains("staged.txt"))
        }
    }
  }

  test("checkCleanFromVcs - raise error listing untracked files when untracked files exist") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      IO.blocking(sbt.IO.write(new File(repo, "untracked.txt"), "new")) *>
        assertFailure[IllegalStateException, VcsOps.CleanCheckResult](
          VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = false)
        ) { err =>
          assert(err.getMessage.contains("untracked files"))
          assert(err.getMessage.contains("untracked.txt"))
        }
    }
  }

  test("checkCleanFromVcs - succeed with untracked files when ignoreUntracked is true") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      IO.blocking(sbt.IO.write(new File(repo, "untracked.txt"), "new")) *>
        VcsOps.checkCleanFromVcs(vcs, ignoreUntracked = true).map { result =>
          assert(result.currentHash.nonEmpty)
        }
    }
  }

  test("detectVcs - detect Git from a loaded sbt state") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, _) =>
      IO.blocking(
        ReleaseTestSupport.gitRootState(repo, Seq(ReleaseIO.releaseIOIgnoreUntrackedFiles := true))
      ).flatMap { state =>
        VcsOps.detectVcs(state).map { vcs =>
          assertEquals(vcs.commandName, "git")
        }
      }
    }
  }

  test("checkCleanWorkingDir(state) - succeed for a clean loaded repo") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, _) =>
      IO.blocking(
        ReleaseTestSupport.gitRootState(repo, Seq(ReleaseIO.releaseIOIgnoreUntrackedFiles := true))
      ).flatMap { state =>
        VcsOps.checkCleanWorkingDir(state).map { result =>
          assert(result.currentHash.nonEmpty)
          assertEquals(result.vcs.commandName, "git")
        }
      }
    }
  }

  test("checkCleanWorkingDir(state, vcs) - read settings from the loaded state") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, _) =>
      IO.blocking(
        ReleaseTestSupport.gitRootState(repo, Seq(ReleaseIO.releaseIOIgnoreUntrackedFiles := true))
      ).flatMap { state =>
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
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
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
    TestSupport.tempDirResource(fixturePrefix).use { outside =>
      ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (_, vcs) =>
        val external = new File(outside, "version.sbt")
        IO.blocking(sbt.IO.write(external, """version := "0.1.0-SNAPSHOT"""")) *>
          assertFailure[IllegalStateException, String](VcsOps.relativizeToBase(vcs, external))(
            err => assert(err.getMessage.contains("outside of VCS root"))
          )
      }
    }
  }

  test("trackedStatus - drop untracked lines from porcelain status") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val vcs = new StubVcs(
        dir,
        status0 = IO.pure(" M tracked.txt\n?? untracked.txt\nA  staged.txt")
      )

      VcsOps.trackedStatus(vcs).map { status =>
        assertEquals(status, " M tracked.txt\nA  staged.txt")
      }
    }
  }

  test("StubVcs - capture method parameters when requested") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      Ref.of[IO, Vector[StubVcsCall]](Vector.empty).flatMap { calls =>
        val vcs = new StubVcs(dir, recordedCalls0 = Some(calls))

        for {
          _    <- vcs.existsTag("v1.0.0")
          _    <- vcs.checkRemote("origin")
          _    <- vcs.add("version.sbt", "build.sbt")
          _    <- vcs.commit("release", sign = true, signOff = false)
          _    <- vcs.tag("v1.0.0", "Release 1.0.0", sign = false, force = true)
          _    <- vcs.pushChanges
          seen <- calls.get
        } yield assertEquals(
          seen,
          Vector(
            StubVcsCall.ExistsTag("v1.0.0"),
            StubVcsCall.CheckRemote("origin"),
            StubVcsCall.Add(List("version.sbt", "build.sbt")),
            StubVcsCall.Commit("release", sign = true, signOff = false),
            StubVcsCall.Tag("v1.0.0", "Release 1.0.0", sign = false, force = true),
            StubVcsCall.PushChanges
          )
        )
      }
    }
  }

  test("StubVcs - run canned write effects after recording") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      Ref.of[IO, Vector[StubVcsCall]](Vector.empty).flatMap { calls =>
        Ref.of[IO, Vector[String]](Vector.empty).flatMap { effects =>
          val vcs = new StubVcs(
            dir,
            recordedCalls0 = Some(calls),
            add0 = effects.update(_ :+ "add"),
            commit0 = effects.update(_ :+ "commit"),
            tag0 = effects.update(_ :+ "tag"),
            pushChanges0 = effects.update(_ :+ "push")
          )

          for {
            _           <- vcs.add("version.sbt")
            _           <- vcs.commit("release", sign = false, signOff = true)
            _           <- vcs.tag("v1.0.0", "Release", sign = true, force = false)
            _           <- vcs.pushChanges
            seenCalls   <- calls.get
            seenEffects <- effects.get
          } yield {
            assertEquals(
              seenCalls,
              Vector(
                StubVcsCall.Add(List("version.sbt")),
                StubVcsCall.Commit("release", sign = false, signOff = true),
                StubVcsCall.Tag("v1.0.0", "Release", sign = true, force = false),
                StubVcsCall.PushChanges
              )
            )
            assertEquals(seenEffects, Vector("add", "commit", "tag", "push"))
          }
        }
      }
    }
  }

  test("validatePushRemote - succeed when the tracking remote is reachable") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val ctx = VcsOpsSpec.promptContext(
        VcsOpsSpec.bufferedState(dir).state,
        interactive = false,
        useDefaults = false
      )

      VcsOps
        .validatePushRemote(
          ctx,
          new StubVcs(dir, checkRemote0 = IO.pure(0)),
          ReleaseLogPrefixes.Core
        )
        .map(result => assertEquals(result, ctx))
    }
  }

  test("releaseIOVcsRemoteCheckTimeout - default fallback is 60 seconds") {
    IO(assertEquals(VcsOps.DefaultRemoteCheckTimeout, 60.seconds))
  }

  test("validatePushRemote - pass the configured timeout into the VCS layer") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      Ref.of[IO, Vector[StubVcsCall]](Vector.empty).flatMap { calls =>
        val logged = VcsOpsSpec.bufferedState(
          dir,
          Seq(ReleaseIO.releaseIOVcsRemoteCheckTimeout := 5.millis)
        )
        val ctx    = VcsOpsSpec.promptContext(logged.state, interactive = false, useDefaults = false)

        VcsOps
          .validatePushRemote(
            ctx,
            new StubVcs(
              dir,
              recordedCalls0 = Some(calls),
              checkRemoteWithTimeout0 = Some((_, _) => IO.pure(Some(0)))
            ),
            ReleaseLogPrefixes.Core
          )
          .flatMap(_ => calls.get)
          .map(seen =>
            assertEquals(
              seen,
              Vector(StubVcsCall.CheckRemoteWithTimeout("origin", 5.millis))
            )
          )
      }
    }
  }

  test("validatePushRemote - use the configured remote-check timeout") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val logged = VcsOpsSpec.bufferedState(
        dir,
        Seq(ReleaseIO.releaseIOVcsRemoteCheckTimeout := 5.millis)
      )
      val ctx    = VcsOpsSpec.promptContext(logged.state, interactive = false, useDefaults = false)

      assertIllegalStateMessage(
        VcsOps
          .validatePushRemote(
            ctx,
            new StubVcs(dir, checkRemote0 = IO.never),
            ReleaseLogPrefixes.Monorepo
          )
          .timeout(1.second),
        "Aborting the release due to remote check failure."
      ) *> IO.blocking {
        val log = logged.consoleBuffer.toString("UTF-8")
        assert(log.contains(s"${ReleaseLogPrefixes.Monorepo} Remote check timed out after"))
      }
    }
  }

  test("validatePushRemote - surface timeout lookup failures instead of falling back") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      Ref.of[IO, Vector[StubVcsCall]](Vector.empty).flatMap { calls =>
        assertFailure[RuntimeException, ReleaseContext](
          VcsOps.validatePushRemote(
            VcsOpsSpec.promptContext(
              TestSupport.dummyState(dir),
              interactive = false,
              useDefaults = false
            ),
            new StubVcs(dir, recordedCalls0 = Some(calls)),
            ReleaseLogPrefixes.Core
          )
        )(err => assert(err.getMessage.contains("Session not initialized"))) *>
          calls.get.map { seen =>
            assert(!seen.exists {
              case StubVcsCall.CheckRemote(_) => true
              case _                          => false
            })
          }
      }
    }
  }

  test("validatePushRemote - abort in non-interactive mode when the remote check fails") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val ctx = VcsOpsSpec.promptContext(
        VcsOpsSpec.bufferedState(dir).state,
        interactive = false,
        useDefaults = false
      )

      assertIllegalStateMessage(
        VcsOps.validatePushRemote(
          ctx,
          new StubVcs(dir, checkRemote0 = IO.pure(1)),
          ReleaseLogPrefixes.Core
        ),
        "Aborting the release due to remote check failure."
      )
    }
  }

  test("validatePushReadiness - fail when no tracking branch is configured") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      assertFailure[IllegalStateException, ReleaseContext](
        VcsOps.validatePushReadiness(
          VcsOpsSpec.promptContext(
            TestSupport.dummyState(dir),
            interactive = false,
            useDefaults = false
          ),
          new StubVcs(dir, hasUpstream0 = IO.pure(false), currentBranch0 = IO.pure("feature"))
        )
      )(err => assert(err.getMessage.contains("No tracking branch configured for 'feature'")))
    }
  }

  test("validatePushReadiness - fail with a clear message when HEAD is detached") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(repo)
      val ctx   = VcsOpsSpec
        .promptContext(
          state,
          interactive = false,
          useDefaults = false
        )
        .withVcs(vcs)

      IO.blocking(TestSupport.runGit(repo, "checkout", "--detach", "HEAD")) *>
        assertIllegalStateMessage(
          VcsOps.validatePushReadiness(ctx, vcs),
          "HEAD is detached. release-io branch-based VCS operations require a checked-out branch."
        )
    }
  }

  test("validatePushReadiness - abort in non-interactive mode when local is behind remote") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      assertIllegalStateMessage(
        VcsOps.validatePushReadiness(
          VcsOpsSpec.promptContext(
            TestSupport.dummyState(dir),
            interactive = false,
            useDefaults = false
          ),
          new StubVcs(dir, isBehindRemote0 = IO.pure(true))
        ),
        "Merge the upstream commits and run release again."
      )
    }
  }

  test("validatePushReadiness - ignore isBehindRemote errors and continue") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val ctx = VcsOpsSpec.promptContext(
        TestSupport.dummyState(dir),
        interactive = false,
        useDefaults = false
      )

      VcsOps
        .validatePushReadiness(
          ctx,
          new StubVcs(dir, isBehindRemote0 = IO.raiseError(new RuntimeException("boom")))
        )
        .map(result => assertEquals(result, ctx))
    }
  }

  test("interactivePushAfterRemote - non-interactive runs doPush") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val ctx = VcsOpsSpec.promptContext(
        VcsOpsSpec.bufferedState(dir).state,
        interactive = false,
        useDefaults = false
      )

      for {
        pushed     <- Ref[IO].of(false)
        declined   <- Ref[IO].of(false)
        vcs         = new StubVcs(dir)
        _          <- VcsOps.interactivePushAfterRemote(
                        ctx,
                        vcs,
                        ReleaseLogPrefixes.Core,
                        remoteCheckLog = None
                      )(
                        doPush = currentCtx => pushed.set(true).as(currentCtx),
                        onDeclinePush = currentCtx => declined.set(true).as(currentCtx)
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
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val ctx = VcsOpsSpec.promptContext(
        VcsOpsSpec.bufferedState(dir).state,
        interactive = true,
        useDefaults = true
      )

      for {
        pushed     <- Ref[IO].of(false)
        declined   <- Ref[IO].of(false)
        vcs         = new StubVcs(dir)
        _          <- VcsOps.interactivePushAfterRemote(
                        ctx,
                        vcs,
                        ReleaseLogPrefixes.Core,
                        remoteCheckLog = None
                      )(
                        doPush = currentCtx => pushed.set(true).as(currentCtx),
                        onDeclinePush = currentCtx => declined.set(true).as(currentCtx)
                      )
        didPush    <- pushed.get
        didDecline <- declined.get
      } yield {
        assertEquals(didPush, true)
        assertEquals(didDecline, false)
      }
    }
  }

  test("interactivePushAfterRemote - blank input keeps the default yes answer") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val ctx = VcsOpsSpec.promptContext(
        VcsOpsSpec.bufferedState(dir).state,
        interactive = true,
        useDefaults = false
      )

      for {
        pushed     <- Ref[IO].of(false)
        declined   <- Ref[IO].of(false)
        vcs         = new StubVcs(dir)
        _          <- TestSupport.withInput("\n") {
                        VcsOps.interactivePushAfterRemote(
                          ctx,
                          vcs,
                          ReleaseLogPrefixes.Core,
                          remoteCheckLog = None
                        )(
                          doPush = currentCtx => pushed.set(true).as(currentCtx),
                          onDeclinePush = currentCtx => declined.set(true).as(currentCtx)
                        )
                      }
        didPush    <- pushed.get
        didDecline <- declined.get
      } yield {
        assertEquals(didPush, true)
        assertEquals(didDecline, false)
      }
    }
  }

  test("interactivePushAfterRemote - re-prompt on invalid push input until a valid answer") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val ctx = VcsOpsSpec.promptContext(
        VcsOpsSpec.bufferedState(dir).state,
        interactive = true,
        useDefaults = false
      )

      for {
        pushed     <- Ref[IO].of(false)
        declined   <- Ref[IO].of(false)
        vcs         = new StubVcs(dir)
        _          <- TestSupport.withInput("maybe\ny\n") {
                        VcsOps.interactivePushAfterRemote(
                          ctx,
                          vcs,
                          ReleaseLogPrefixes.Core,
                          remoteCheckLog = None
                        )(
                          doPush = currentCtx => pushed.set(true).as(currentCtx),
                          onDeclinePush = currentCtx => declined.set(true).as(currentCtx)
                        )
                      }
        didPush    <- pushed.get
        didDecline <- declined.get
      } yield {
        assertEquals(didPush, true)
        assertEquals(didDecline, false)
      }
    }
  }

  test("interactivePushAfterRemote - closed stdin declines push and logs a warning") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val buffered = VcsOpsSpec.bufferedState(dir)
      val ctx      = VcsOpsSpec.promptContext(
        buffered.state,
        interactive = true,
        useDefaults = false
      )

      for {
        pushed     <- Ref[IO].of(false)
        declined   <- Ref[IO].of(false)
        vcs         = new StubVcs(dir)
        _          <- TestSupport.withInput("") {
                        VcsOps.interactivePushAfterRemote(
                          ctx,
                          vcs,
                          ReleaseLogPrefixes.Core,
                          remoteCheckLog = None
                        )(
                          doPush = currentCtx => pushed.set(true).as(currentCtx),
                          onDeclinePush = currentCtx => declined.set(true).as(currentCtx)
                        )
                      }
        didPush    <- pushed.get
        didDecline <- declined.get
        log        <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        val warning =
          s"${ReleaseLogPrefixes.Core} Standard input closed before push confirmation. Skipping push."
        assertEquals(didPush, false)
        assertEquals(didDecline, true)
        assertEquals(log.sliding(warning.length).count(_ == warning), 1)
      }
    }
  }

  test(
    "interactivePushAfterRemote - preserve CRLF prompt state from remote-check confirmation into push confirmation"
  ) {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val ctx = VcsOpsSpec.promptContext(
        VcsOpsSpec.bufferedState(dir).state,
        interactive = true,
        useDefaults = false
      )

      for {
        pushed     <- Ref[IO].of(false)
        declined   <- Ref[IO].of(false)
        vcs         = new StubVcs(dir, checkRemote0 = IO.pure(1))
        _          <- TestSupport.withInput("y\r\nn\r\n") {
                        VcsOps.interactivePushAfterRemote(
                          ctx,
                          vcs,
                          ReleaseLogPrefixes.Core,
                          remoteCheckLog = None
                        )(
                          doPush = currentCtx => pushed.set(true).as(currentCtx),
                          onDeclinePush = currentCtx => declined.set(true).as(currentCtx)
                        )
                      }
        didPush    <- pushed.get
        didDecline <- declined.get
      } yield {
        assertEquals(didPush, false)
        assertEquals(didDecline, true)
      }
    }
  }

}

private object VcsOpsSpec {
  private val defaultFlags = ExecutionFlags(
    useDefaults = false,
    skipTests = false,
    skipPublish = false,
    interactive = false,
    crossBuild = false
  )

  final case class BufferedState(
      state: State,
      consoleBuffer: ByteArrayOutputStream
  )

  def promptContext(
      state: State,
      interactive: Boolean,
      useDefaults: Boolean
  ): ReleaseContext =
    ReleaseContext(state = state, interactive = interactive).withExecutionState(
      CoreExecutionState(
        CoreReleasePlan(
          flags = defaultFlags.copy(
            useDefaults = useDefaults,
            interactive = interactive
          ),
          releaseVersionOverride = None,
          nextVersionOverride = None,
          decisionDefaults = ReleaseDecisionDefaults.empty
        )
      )
    )

  def bufferedState(
      dir: File,
      rootSettings: Seq[Setting[?]] = Nil
  ): BufferedState = {
    val logFile       = new File(dir, "sbt-test.log")
    val consoleBuffer = new ByteArrayOutputStream()
    val consoleOut    = ConsoleOut.printStreamOut(new PrintStream(consoleBuffer))
    val globalLogging =
      GlobalLogging.initial(
        MainAppender.globalDefault(consoleOut),
        logFile,
        consoleOut
      )
    val baseState     = State(
      configuration = TestSupport.dummyAppConfiguration(dir),
      definedCommands = Nil,
      exitHooks = Set.empty,
      onFailure = None,
      remainingCommands = Nil,
      history = State.newHistory,
      attributes = AttributeMap.empty,
      globalLogging = globalLogging,
      currentCommand = None,
      next = State.Continue
    )

    BufferedState(
      state = sbt.TestBuildState(
        baseState = baseState,
        baseDir = dir,
        projects = Seq(Project("root", dir).settings(rootSettings*)),
        currentProjectId = Some("root")
      ),
      consoleBuffer = consoleBuffer
    )
  }
}

private final class StubVcs(
    override val baseDir: File,
    currentHash0: IO[String] = IO.pure("abc"),
    currentBranch0: IO[String] = IO.pure("main"),
    trackingRemote0: IO[String] = IO.pure("origin"),
    upstreamTrackingHash0: IO[Option[String]] = IO.pure(Some("origin/main")),
    hasUpstream0: IO[Boolean] = IO.pure(true),
    isBehindRemote0: IO[Boolean] = IO.pure(false),
    existsTag0: IO[Boolean] = IO.pure(false),
    modifiedFiles0: IO[Seq[String]] = IO.pure(Nil),
    stagedFiles0: IO[Seq[String]] = IO.pure(Nil),
    untrackedFiles0: IO[Seq[String]] = IO.pure(Nil),
    status0: IO[String] = IO.pure(""),
    checkRemote0: IO[Int] = IO.pure(0),
    checkRemoteWithTimeout0: Option[(String, FiniteDuration) => IO[Option[Int]]] = None,
    add0: IO[Unit] = IO.unit,
    commit0: IO[Unit] = IO.unit,
    tag0: IO[Unit] = IO.unit,
    pushChanges0: IO[Unit] = IO.unit,
    recordedCalls0: Option[Ref[IO, Vector[StubVcsCall]]] = None
) extends Vcs {
  private def record(call: StubVcsCall): IO[Unit] =
    recordedCalls0.fold(IO.unit)(_.update(_ :+ call))

  override def commandName: String = "git"

  override def currentHash: IO[String]                  = currentHash0
  override def currentBranch: IO[String]                = currentBranch0
  override def trackingRemote: IO[String]               = trackingRemote0
  override def upstreamTrackingHash: IO[Option[String]] =
    upstreamTrackingHash0
  override def hasUpstream: IO[Boolean]                 = hasUpstream0
  override def isBehindRemote: IO[Boolean]              = isBehindRemote0
  override def existsTag(name: String): IO[Boolean]     =
    record(StubVcsCall.ExistsTag(name)) *> existsTag0
  override def modifiedFiles: IO[Seq[String]]           = modifiedFiles0
  override def stagedFiles: IO[Seq[String]]             = stagedFiles0
  override def untrackedFiles: IO[Seq[String]]          = untrackedFiles0
  override def status: IO[String]                       = status0
  override def checkRemote(remote: String): IO[Int]     =
    record(StubVcsCall.CheckRemote(remote)) *> checkRemote0

  override def checkRemoteWithTimeout(remote: String, timeout: FiniteDuration): IO[Option[Int]] =
    record(StubVcsCall.CheckRemoteWithTimeout(remote, timeout)) *>
      checkRemoteWithTimeout0.fold(super.checkRemoteWithTimeout(remote, timeout))(
        _.apply(remote, timeout)
      )

  // This stub records write-operation arguments and can optionally run canned effects, but it
  // does not model real VCS mutations.
  override def add(files: String*): IO[Unit] =
    record(StubVcsCall.Add(files.toList)) *> add0

  override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit] =
    record(StubVcsCall.Commit(message, sign, signOff)) *> commit0

  override def tag(name: String, comment: String, sign: Boolean, force: Boolean): IO[Unit] =
    record(StubVcsCall.Tag(name, comment, sign, force)) *> tag0

  override def pushChanges: IO[Unit] =
    record(StubVcsCall.PushChanges) *> pushChanges0
}

private sealed trait StubVcsCall

private object StubVcsCall {
  final case class ExistsTag(name: String)                                  extends StubVcsCall
  final case class CheckRemote(remote: String)                              extends StubVcsCall
  final case class CheckRemoteWithTimeout(remote: String, timeout: FiniteDuration)
      extends StubVcsCall
  final case class Add(files: List[String])                                 extends StubVcsCall
  final case class Commit(message: String, sign: Boolean, signOff: Boolean) extends StubVcsCall
  final case class Tag(name: String, comment: String, sign: Boolean, force: Boolean)
      extends StubVcsCall
  case object PushChanges                                                   extends StubVcsCall
}
