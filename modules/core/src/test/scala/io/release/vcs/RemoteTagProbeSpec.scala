package io.release.vcs

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.ReleaseContext
import io.release.ReleasePluginIO
import io.release.ReleaseTestSupport
import io.release.VcsOps
import io.release.core.internal.CoreExecutionState
import io.release.core.internal.CoreReleasePlan
import io.release.runtime.ExecutionFlags
import io.release.runtime.ReleaseDecisionDefaults
import io.release.runtime.ReleaseLogPrefixes
import munit.CatsEffectSuite
import sbt.Project

import java.io.File
import scala.concurrent.duration.*

class RemoteTagProbeSpec extends CatsEffectSuite {

  private val fixturePrefix = "remote-tag-probe-spec"
  private val LogPrefix     = ReleaseLogPrefixes.Core
  private val CommandName   = "releaseIO"
  private val TagName       = "v0.1.0"

  // ── shouldSkip ─────────────────────────────────────────────────────

  test("shouldSkip - true when pushConfigured = false (push step not in plan)") {
    contextWithFlags(useDefaults = false, interactive = false).use { ctx =>
      IO(assertEquals(RemoteTagProbe.shouldSkip(ctx, pushConfigured = false), true))
    }
  }

  test("shouldSkip - true when push answer is explicitly Some(false) (operator declined)") {
    contextWithPushAnswer(Some(false)).use { ctx =>
      IO(assertEquals(RemoteTagProbe.shouldSkip(ctx, pushConfigured = true), true))
    }
  }

  test(
    "shouldSkip - true when no answer + non-interactive + no useDefaults (deterministic decline)"
  ) {
    contextWithFlags(useDefaults = false, interactive = false).use { ctx =>
      IO(assertEquals(RemoteTagProbe.shouldSkip(ctx, pushConfigured = true), true))
    }
  }

  test("shouldSkip - false when push answer is Some(true) (operator accepted)") {
    contextWithPushAnswer(Some(true)).use { ctx =>
      IO(assertEquals(RemoteTagProbe.shouldSkip(ctx, pushConfigured = true), false))
    }
  }

  test("shouldSkip - false when no answer + useDefaults = true (default-yes path)") {
    contextWithFlags(useDefaults = true, interactive = false).use { ctx =>
      IO(assertEquals(RemoteTagProbe.shouldSkip(ctx, pushConfigured = true), false))
    }
  }

  test("shouldSkip - false when no answer + interactive = true (release will prompt)") {
    contextWithFlags(useDefaults = false, interactive = true).use { ctx =>
      IO(assertEquals(RemoteTagProbe.shouldSkip(ctx, pushConfigured = true), false))
    }
  }

  // ── loadTimeout ────────────────────────────────────────────────────

  test("loadTimeout - returns the shared default when the setting is absent from state") {
    loadedCtxResource.use { ctx =>
      RemoteTagProbe.loadTimeout(ctx.state).map { duration =>
        assertEquals(duration, VcsOps.DefaultRemoteCheckTimeout)
      }
    }
  }

  // ── probeForCreate ─────────────────────────────────────────────────

  test("probeForCreate - skips when pushConfigured = false; no Vcs calls") {
    runProbe(
      buildVcs = StubVcs.recording,
      pushConfigured = false
    ) { (result, calls) =>
      assert(result.isRight, s"expected success, got: $result")
      assertEquals(calls, Nil)
    }
  }

  test("probeForCreate - skips when push is declined (Some(false)); no Vcs calls") {
    runProbe(
      buildVcs = StubVcs.recording,
      pushConfigured = true,
      pushAnswer = Some(false)
    ) { (result, calls) =>
      assert(result.isRight, s"expected success, got: $result")
      assertEquals(calls, Nil)
    }
  }

  test("probeForCreate - exits cleanly when the branch has no upstream; no remote query") {
    runProbe(
      buildVcs = StubVcs.recording(_, hasUpstreamValue = false),
      pushConfigured = true,
      pushAnswer = Some(true)
    ) { (result, calls) =>
      assert(result.isRight, s"expected success, got: $result")
      assertEquals(
        calls,
        List("hasUpstream"),
        "only hasUpstream should be called when there is no upstream"
      )
    }
  }

  test("probeForCreate - returns unit when remote does not have the tag") {
    runProbe(
      buildVcs =
        StubVcs.recording(_, hasUpstreamValue = true, remoteTagExistsValue = Some(Some(false))),
      pushConfigured = true,
      pushAnswer = Some(true)
    ) { (result, calls) =>
      assert(result.isRight, s"expected success, got: $result")
      assertEquals(
        calls,
        List("hasUpstream", "trackingRemote", "remoteTagExistsWithTimeout(origin,v0.1.0)")
      )
    }
  }

  test("probeForCreate - raises IllegalStateException when the tag exists only on the remote") {
    runProbe(
      buildVcs =
        StubVcs.recording(_, hasUpstreamValue = true, remoteTagExistsValue = Some(Some(true))),
      pushConfigured = true,
      pushAnswer = Some(true)
    ) { (result, _) =>
      result match {
        case Left(err: IllegalStateException) =>
          assert(
            err.getMessage.contains("already exists on remote"),
            s"unexpected message: ${err.getMessage}"
          )
          assert(
            err.getMessage.contains("[v0.1.0]"),
            s"missing tag name in message: ${err.getMessage}"
          )
          assert(
            err.getMessage.contains("git fetch origin --tags"),
            s"missing fetch instruction: ${err.getMessage}"
          )
          assert(
            !err.getMessage.contains("--force"),
            s"remote-only message should not advise a force-push: ${err.getMessage}"
          )
          assert(
            err.getMessage.contains(s"$CommandName help"),
            s"missing help reference: ${err.getMessage}"
          )
        case other                            =>
          fail(s"expected IllegalStateException, got: $other")
      }
    }
  }

  test(
    "probeForCreate - on the overwrite path (tag also exists locally) advises a force-push, " +
      "not git fetch"
  ) {
    runProbe(
      buildVcs = StubVcs.recording(
        _,
        hasUpstreamValue = true,
        remoteTagExistsValue = Some(Some(true)),
        existsTagValue = true
      ),
      pushConfigured = true,
      pushAnswer = Some(true)
    ) { (result, _) =>
      result match {
        case Left(err: IllegalStateException) =>
          assert(
            err.getMessage.contains("already exists on remote"),
            s"unexpected message: ${err.getMessage}"
          )
          assert(
            err.getMessage.contains("[v0.1.0]"),
            s"missing tag name in message: ${err.getMessage}"
          )
          assert(
            err.getMessage.contains("git push origin --force refs/tags/v0.1.0"),
            s"missing force-push instruction: ${err.getMessage}"
          )
          assert(
            !err.getMessage.contains("git fetch"),
            s"overwrite message should not advise git fetch: ${err.getMessage}"
          )
          assert(
            err.getMessage.contains(s"$CommandName help"),
            s"missing help reference: ${err.getMessage}"
          )
        case other                            =>
          fail(s"expected IllegalStateException, got: $other")
      }
    }
  }

  test("probeForCreate - degrades to no-error when the remote query times out (None result)") {
    runProbe(
      buildVcs = StubVcs.recording(_, hasUpstreamValue = true, remoteTagExistsValue = Some(None)),
      pushConfigured = true,
      pushAnswer = Some(true)
    ) { (result, calls) =>
      assert(result.isRight, s"expected success on timeout, got: $result")
      assertEquals(
        calls,
        List("hasUpstream", "trackingRemote", "remoteTagExistsWithTimeout(origin,v0.1.0)")
      )
    }
  }

  test("probeForCreate - includes the per-project label suffix in the conflict error message") {
    runProbe(
      buildVcs =
        StubVcs.recording(_, hasUpstreamValue = true, remoteTagExistsValue = Some(Some(true))),
      pushConfigured = true,
      pushAnswer = Some(true),
      label = Some("api")
    ) { (result, _) =>
      result match {
        case Left(err: IllegalStateException) =>
          assert(
            err.getMessage.contains("for api"),
            s"missing label suffix: ${err.getMessage}"
          )
        case other                            =>
          fail(s"expected IllegalStateException, got: $other")
      }
    }
  }

  // ── probeForKeep ───────────────────────────────────────────────────

  private val KeptCommit  = "1111111111111111111111111111111111111111"
  private val OtherCommit = "2222222222222222222222222222222222222222"

  test("probeForKeep - skips when pushConfigured = false; no Vcs calls") {
    runKeepProbe(
      buildVcs = StubVcs.recording,
      pushConfigured = false
    ) { (result, calls) =>
      assert(result.isRight, s"expected success, got: $result")
      assertEquals(calls, Nil)
    }
  }

  test("probeForKeep - exits cleanly when the branch has no upstream; no remote query") {
    runKeepProbe(
      buildVcs = StubVcs.recording(_, hasUpstreamValue = false),
      pushConfigured = true,
      pushAnswer = Some(true)
    ) { (result, calls) =>
      assert(result.isRight, s"expected success, got: $result")
      assertEquals(calls, List("hasUpstream"))
    }
  }

  test("probeForKeep - returns unit when the remote does not have the tag (Absent)") {
    runKeepProbe(
      buildVcs = StubVcs
        .recording(_, hasUpstreamValue = true, remoteTagCommitValue = Some(RemoteTagCommit.Absent)),
      pushConfigured = true,
      pushAnswer = Some(true)
    ) { (result, calls) =>
      assert(result.isRight, s"expected success, got: $result")
      assertEquals(
        calls,
        List("hasUpstream", "trackingRemote", "remoteTagCommitWithTimeout(origin,v0.1.0)")
      )
    }
  }

  test("probeForKeep - returns unit when the remote tag is at the same commit (no-op push)") {
    runKeepProbe(
      buildVcs = StubVcs.recording(
        _,
        hasUpstreamValue = true,
        remoteTagCommitValue = Some(RemoteTagCommit.At(KeptCommit))
      ),
      pushConfigured = true,
      pushAnswer = Some(true),
      expectedCommitHash = KeptCommit
    ) { (result, calls) =>
      assert(result.isRight, s"expected success, got: $result")
      assertEquals(
        calls,
        List("hasUpstream", "trackingRemote", "remoteTagCommitWithTimeout(origin,v0.1.0)")
      )
    }
  }

  test("probeForKeep - raises IllegalStateException when the remote tag is at a different commit") {
    runKeepProbe(
      buildVcs = StubVcs.recording(
        _,
        hasUpstreamValue = true,
        remoteTagCommitValue = Some(RemoteTagCommit.At(OtherCommit))
      ),
      pushConfigured = true,
      pushAnswer = Some(true),
      expectedCommitHash = KeptCommit
    ) { (result, _) =>
      result match {
        case Left(err: IllegalStateException) =>
          assert(
            err.getMessage.contains("different commit"),
            s"unexpected message: ${err.getMessage}"
          )
          assert(
            err.getMessage.contains("[v0.1.0]"),
            s"missing tag name in message: ${err.getMessage}"
          )
          assert(
            err.getMessage.contains(KeptCommit) && err.getMessage.contains(OtherCommit),
            s"missing commit hashes in message: ${err.getMessage}"
          )
          assert(
            err.getMessage.contains("git push origin --force refs/tags/v0.1.0"),
            s"missing force-push instruction: ${err.getMessage}"
          )
          assert(
            err.getMessage.contains(s"$CommandName help"),
            s"missing help reference: ${err.getMessage}"
          )
        case other                            =>
          fail(s"expected IllegalStateException, got: $other")
      }
    }
  }

  test("probeForKeep - degrades to no-error when the remote query is Unavailable") {
    runKeepProbe(
      buildVcs = StubVcs.recording(
        _,
        hasUpstreamValue = true,
        remoteTagCommitValue = Some(RemoteTagCommit.Unavailable)
      ),
      pushConfigured = true,
      pushAnswer = Some(true)
    ) { (result, calls) =>
      assert(result.isRight, s"expected success on unavailable remote, got: $result")
      assertEquals(
        calls,
        List("hasUpstream", "trackingRemote", "remoteTagCommitWithTimeout(origin,v0.1.0)")
      )
    }
  }

  test("probeForKeep - includes the per-project label suffix in the conflict error message") {
    runKeepProbe(
      buildVcs = StubVcs.recording(
        _,
        hasUpstreamValue = true,
        remoteTagCommitValue = Some(RemoteTagCommit.At(OtherCommit))
      ),
      pushConfigured = true,
      pushAnswer = Some(true),
      expectedCommitHash = KeptCommit,
      label = Some("api")
    ) { (result, _) =>
      result match {
        case Left(err: IllegalStateException) =>
          assert(err.getMessage.contains("for api"), s"missing label suffix: ${err.getMessage}")
        case other                            =>
          fail(s"expected IllegalStateException, got: $other")
      }
    }
  }

  // ── helpers ────────────────────────────────────────────────────────

  /** Loaded sbt context — needed by `loadTimeout` and the deeper `probeForCreate`
    * branches because both eventually touch `Project.extract(state)` via
    * `SbtRuntime.extracted`.
    */
  private def loadedCtxResource: Resource[IO, ReleaseContext] =
    ReleaseTestSupport.loadedContextResource(fixturePrefix)(dir =>
      Seq(Project("root", dir).enablePlugins(ReleasePluginIO))
    )

  private def contextWithFlags(
      useDefaults: Boolean,
      interactive: Boolean,
      decisionDefaults: ReleaseDecisionDefaults = ReleaseDecisionDefaults.empty
  ): Resource[IO, ReleaseContext] =
    loadedCtxResource.map { ctx =>
      ctx
        .copy(interactive = interactive)
        .withExecutionState(
          CoreExecutionState(
            CoreReleasePlan(
              flags = ExecutionFlags(
                useDefaults = useDefaults,
                skipTests = false,
                skipPublish = false,
                interactive = interactive,
                crossBuild = false
              ),
              releaseVersionOverride = None,
              nextVersionOverride = None,
              decisionDefaults = decisionDefaults
            )
          )
        )
    }

  private def contextWithPushAnswer(
      answer: Option[Boolean]
  ): Resource[IO, ReleaseContext] =
    contextWithFlags(
      useDefaults = false,
      interactive = false,
      decisionDefaults = ReleaseDecisionDefaults.empty.copy(pushAnswer = answer)
    )

  private def runProbe(
      buildVcs: Ref[IO, List[String]] => StubVcs,
      pushConfigured: Boolean,
      pushAnswer: Option[Boolean] = None,
      label: Option[String] = None
  )(verify: (Either[Throwable, Unit], List[String]) => Unit): IO[Unit] =
    contextWithPushAnswer(pushAnswer).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { calls =>
        val vcs = buildVcs(calls)
        RemoteTagProbe
          .probeForCreate(ctx, vcs, TagName, CommandName, LogPrefix, label, pushConfigured)
          .attempt
          .flatMap { result =>
            calls.get.map(recorded => verify(result, recorded.reverse))
          }
      }
    }

  private def runKeepProbe(
      buildVcs: Ref[IO, List[String]] => StubVcs,
      pushConfigured: Boolean,
      pushAnswer: Option[Boolean] = None,
      label: Option[String] = None,
      expectedCommitHash: String = KeptCommit
  )(verify: (Either[Throwable, Unit], List[String]) => Unit): IO[Unit] =
    contextWithPushAnswer(pushAnswer).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { calls =>
        val vcs = buildVcs(calls)
        RemoteTagProbe
          .probeForKeep(
            ctx,
            vcs,
            TagName,
            expectedCommitHash,
            CommandName,
            LogPrefix,
            label,
            pushConfigured
          )
          .attempt
          .flatMap { result =>
            calls.get.map(recorded => verify(result, recorded.reverse))
          }
      }
    }
}

/** Minimal stub `Vcs` for [[RemoteTagProbeSpec]]. Records each invoked method
  * into the supplied `Ref`; any method not exercised by the probe pipeline is
  * left unimplemented and raises if called, surfacing accidental Vcs use as a
  * test failure rather than silent zero-value behavior.
  */
private final class StubVcs(
    callsRef: Ref[IO, List[String]],
    hasUpstreamValue: Boolean,
    trackingRemoteValue: String,
    remoteTagExistsValue: Option[Option[Boolean]],
    existsTagValue: Boolean,
    remoteTagCommitValue: Option[RemoteTagCommit] = None
) extends Vcs {
  override val baseDir: File       = new File(".")
  override val commandName: String = "git"

  private def record(label: String): IO[Unit] =
    callsRef.update(label :: _)

  override def hasUpstream: IO[Boolean] =
    record("hasUpstream") *> IO.pure(hasUpstreamValue)

  override def trackingRemote: IO[String] =
    record("trackingRemote") *> IO.pure(trackingRemoteValue)

  override def remoteTagExistsWithTimeout(
      remote: String,
      tagName: String,
      timeout: FiniteDuration
  ): IO[Option[Boolean]] =
    record(s"remoteTagExistsWithTimeout($remote,$tagName)") *>
      remoteTagExistsValue.fold(
        IO.raiseError[Option[Boolean]](
          new AssertionError("remoteTagExistsWithTimeout should not be reached")
        )
      )(IO.pure)

  override def remoteTagCommitWithTimeout(
      remote: String,
      tagName: String,
      timeout: FiniteDuration
  ): IO[RemoteTagCommit] =
    record(s"remoteTagCommitWithTimeout($remote,$tagName)") *>
      remoteTagCommitValue.fold(
        IO.raiseError[RemoteTagCommit](
          new AssertionError("remoteTagCommitWithTimeout should not be reached")
        )
      )(IO.pure)

  // The probe never calls these; surface accidental use as a clear test failure.
  override def currentHash: IO[String]                                                     =
    IO.raiseError(new AssertionError("currentHash should not be reached"))
  override def currentBranch: IO[String]                                                   =
    IO.raiseError(new AssertionError("currentBranch should not be reached"))
  override def upstreamTrackingHash: IO[Option[String]]                                    =
    IO.raiseError(new AssertionError("upstreamTrackingHash should not be reached"))
  override def isBehindRemote: IO[Boolean]                                                 =
    IO.raiseError(new AssertionError("isBehindRemote should not be reached"))
  override def existsTag(name: String): IO[Boolean]                                        =
    record(s"existsTag($name)") *> IO.pure(existsTagValue)
  override def modifiedFiles: IO[Seq[String]]                                              =
    IO.raiseError(new AssertionError("modifiedFiles should not be reached"))
  override def stagedFiles: IO[Seq[String]]                                                =
    IO.raiseError(new AssertionError("stagedFiles should not be reached"))
  override def untrackedFiles: IO[Seq[String]]                                             =
    IO.raiseError(new AssertionError("untrackedFiles should not be reached"))
  override def status: IO[String]                                                          =
    IO.raiseError(new AssertionError("status should not be reached"))
  override def checkRemote(remote: String): IO[Int]                                        =
    IO.raiseError(new AssertionError("checkRemote should not be reached"))
  override def add(files: String*): IO[Unit]                                               =
    IO.raiseError(new AssertionError("add should not be reached"))
  override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit]          =
    IO.raiseError(new AssertionError("commit should not be reached"))
  override def tag(name: String, comment: String, sign: Boolean, force: Boolean): IO[Unit] =
    IO.raiseError(new AssertionError("tag should not be reached"))
  override def pushChanges: IO[Unit]                                                       =
    IO.raiseError(new AssertionError("pushChanges should not be reached"))
}

private object StubVcs {

  /** Builder used by `RemoteTagProbeSpec` tests to construct a `StubVcs` from a
    * fresh recording `Ref` while overriding only the values relevant to the
    * branch under test.
    */
  def recording(
      callsRef: Ref[IO, List[String]],
      hasUpstreamValue: Boolean = true,
      trackingRemoteValue: String = "origin",
      remoteTagExistsValue: Option[Option[Boolean]] = None,
      existsTagValue: Boolean = false,
      remoteTagCommitValue: Option[RemoteTagCommit] = None
  ): StubVcs =
    new StubVcs(
      callsRef,
      hasUpstreamValue,
      trackingRemoteValue,
      remoteTagExistsValue,
      existsTagValue,
      remoteTagCommitValue
    )

  def recording(callsRef: Ref[IO, List[String]]): StubVcs =
    recording(
      callsRef = callsRef,
      hasUpstreamValue = true,
      trackingRemoteValue = "origin",
      remoteTagExistsValue = None,
      existsTagValue = false
    )
}
