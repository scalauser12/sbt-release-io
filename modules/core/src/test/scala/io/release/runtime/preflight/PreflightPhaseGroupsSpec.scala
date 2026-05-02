package io.release.runtime.preflight

import cats.effect.IO
import cats.effect.Ref
import io.release.runtime.HookPhases
import io.release.vcs.TagConflictResolver
import munit.CatsEffectSuite

class PreflightPhaseGroupsSpec extends CatsEffectSuite {

  // ── Phase set constants ────────────────────────────────────────────

  test("TagPreflightRelevantPhases - covers post-version-resolution phases through before-tag") {
    assertEquals(
      PreflightPhaseGroups.TagPreflightRelevantPhases,
      Set(
        HookPhases.AfterVersionResolution,
        HookPhases.BeforeReleaseVersionWrite,
        HookPhases.AfterReleaseVersionWrite,
        HookPhases.BeforeReleaseCommit,
        HookPhases.AfterReleaseCommit,
        HookPhases.BeforeTag
      )
    )
  }

  test("PostTagVersionMutationPhases - covers after-tag through before-next-commit") {
    assertEquals(
      PreflightPhaseGroups.PostTagVersionMutationPhases,
      Set(
        HookPhases.AfterTag,
        HookPhases.BeforePublish,
        HookPhases.AfterPublish,
        HookPhases.BeforeNextVersionWrite,
        HookPhases.AfterNextVersionWrite,
        HookPhases.BeforeNextCommit
      )
    )
  }

  test("VersionSummaryMutationPhases - is the union of the two phase sets") {
    assertEquals(
      PreflightPhaseGroups.VersionSummaryMutationPhases,
      PreflightPhaseGroups.TagPreflightRelevantPhases ++
        PreflightPhaseGroups.PostTagVersionMutationPhases
    )
  }

  test("VersionSummaryMutationPhases - the two component sets are disjoint") {
    val intersection = PreflightPhaseGroups.TagPreflightRelevantPhases &
      PreflightPhaseGroups.PostTagVersionMutationPhases
    assertEquals(intersection, Set.empty[String])
  }

  // ── tagAffectingPhases ─────────────────────────────────────────────

  test("tagAffectingPhases - empty input returns just the tag-preflight-relevant phases") {
    assertEquals(
      PreflightPhaseGroups.tagAffectingPhases(Set.empty),
      PreflightPhaseGroups.TagPreflightRelevantPhases
    )
  }

  test("tagAffectingPhases - disjoint input grows the set") {
    val extra  = Set(HookPhases.AfterCleanCheck, HookPhases.BeforeVersionResolution)
    val result = PreflightPhaseGroups.tagAffectingPhases(extra)
    assertEquals(result, PreflightPhaseGroups.TagPreflightRelevantPhases ++ extra)
  }

  test("tagAffectingPhases - overlapping input is deduped via set union") {
    val overlap = Set(HookPhases.BeforeTag, HookPhases.AfterCleanCheck)
    val result  = PreflightPhaseGroups.tagAffectingPhases(overlap)
    assertEquals(
      result,
      PreflightPhaseGroups.TagPreflightRelevantPhases + HookPhases.AfterCleanCheck
    )
  }

  // ── tagPreflightEnabled ────────────────────────────────────────────

  test("tagPreflightEnabled - false when tagging policy is disabled") {
    assertEquals(PreflightPhaseGroups.tagPreflightEnabled(enableTagging = false), false)
    assertEquals(
      PreflightPhaseGroups.tagPreflightEnabled(enableTagging = false, false, false, false),
      false
    )
  }

  test("tagPreflightEnabled - true when tagging is enabled and no opt-in flag is set") {
    assertEquals(PreflightPhaseGroups.tagPreflightEnabled(enableTagging = true), true)
    assertEquals(
      PreflightPhaseGroups.tagPreflightEnabled(enableTagging = true, false, false, false, false),
      true
    )
  }

  test("tagPreflightEnabled - false when any single opt-in flag is true") {
    assertEquals(PreflightPhaseGroups.tagPreflightEnabled(true, true), false)
    assertEquals(PreflightPhaseGroups.tagPreflightEnabled(true, false, true, false), false)
    assertEquals(PreflightPhaseGroups.tagPreflightEnabled(true, false, false, true), false)
  }

  test("tagPreflightEnabled - all-true opt-ins still disable preflight") {
    assertEquals(PreflightPhaseGroups.tagPreflightEnabled(true, true, true, true, true), false)
  }

  // ── hasHookPhase ───────────────────────────────────────────────────

  test("hasHookPhase - matches step names starting with `$phase:`") {
    val stepNames = Seq("before-tag:my-hook", "tag-release", "after-tag:my-other-hook")
    assertEquals(PreflightPhaseGroups.hasHookPhase(stepNames, HookPhases.BeforeTag), true)
    assertEquals(PreflightPhaseGroups.hasHookPhase(stepNames, HookPhases.AfterTag), true)
  }

  test("hasHookPhase - does not match step names that merely contain the phase") {
    val stepNames = Seq("custom-before-tag-step", "tag-release")
    assertEquals(PreflightPhaseGroups.hasHookPhase(stepNames, HookPhases.BeforeTag), false)
  }

  test("hasHookPhase - does not match step names equal to the bare phase") {
    // The compiler emits `"<phase>:<hookName>"`; a bare `"<phase>"` step is a built-in
    // step in that lifecycle slot, NOT a hook installation, so it must not match.
    val stepNames = Seq("before-tag", "tag-release")
    assertEquals(PreflightPhaseGroups.hasHookPhase(stepNames, HookPhases.BeforeTag), false)
  }

  test("hasHookPhase - empty step names returns false") {
    assertEquals(PreflightPhaseGroups.hasHookPhase(Nil, HookPhases.BeforeTag), false)
  }

  // ── anyPhasePresent ────────────────────────────────────────────────

  test("anyPhasePresent - empty phase set returns false") {
    val stepNames = Seq("before-tag:my-hook")
    assertEquals(PreflightPhaseGroups.anyPhasePresent(stepNames, Set.empty), false)
  }

  test("anyPhasePresent - returns true when any phase is present") {
    val stepNames = Seq("after-tag:my-hook", "publish-artifacts")
    val phases    = Set(HookPhases.BeforeTag, HookPhases.AfterTag, HookPhases.AfterPublish)
    assertEquals(PreflightPhaseGroups.anyPhasePresent(stepNames, phases), true)
  }

  test("anyPhasePresent - returns false when no phase matches") {
    val stepNames = Seq("tag-release", "publish-artifacts")
    val phases    = Set(HookPhases.BeforeTag, HookPhases.AfterTag)
    assertEquals(PreflightPhaseGroups.anyPhasePresent(stepNames, phases), false)
  }

  // ── dispatchPreflightTag ───────────────────────────────────────────

  test(
    "dispatchPreflightTag - skips wouldChange and calls runPreflight(None) when builtIn=false"
  ) {
    Ref.of[IO, Boolean](false).flatMap { wouldChangeEvaluated =>
      val wouldChange: IO[Boolean] = wouldChangeEvaluated.set(true).as(false)
      PreflightPhaseGroups
        .dispatchPreflightTag(
          builtInIncludesReleaseWriteAndCommit = false,
          wouldChange = wouldChange,
          runPreflight = override_ => IO.pure(override_.isDefined)
        )
        .flatMap { observedHadOverride =>
          wouldChangeEvaluated.get.map { evaluated =>
            assertEquals(
              observedHadOverride,
              false,
              "runPreflight must be invoked with None when builtIn=false"
            )
            assertEquals(
              evaluated,
              false,
              "wouldChange must not be evaluated when builtIn=false"
            )
          }
        }
    }
  }

  test(
    "dispatchPreflightTag - calls runPreflight(None) when builtIn=true and wouldChange=false"
  ) {
    PreflightPhaseGroups
      .dispatchPreflightTag(
        builtInIncludesReleaseWriteAndCommit = true,
        wouldChange = IO.pure(false),
        runPreflight = override_ => IO.pure(override_.isDefined)
      )
      .map(observed => assertEquals(observed, false))
  }

  test(
    "dispatchPreflightTag - calls runPreflight(Some(_ => FutureReleaseCommit)) when builtIn=true and wouldChange=true"
  ) {
    PreflightPhaseGroups
      .dispatchPreflightTag(
        builtInIncludesReleaseWriteAndCommit = true,
        wouldChange = IO.pure(true),
        runPreflight = {
          case Some(callback) =>
            // Verify the override returns FutureReleaseCommit regardless of which Vcs
            // it would receive — the dispatcher's contract is to pin the target type,
            // not to query the Vcs.
            callback(null).map { target =>
              assertEquals(target, TagConflictResolver.PreflightCommitTarget.FutureReleaseCommit)
              "future-commit"
            }
          case None           =>
            IO.raiseError(
              new AssertionError("expected Some(callback) when wouldChange=true")
            )
        }
      )
      .map(observed => assertEquals(observed, "future-commit"))
  }

  test("dispatchPreflightTag - propagates the result type unchanged") {
    PreflightPhaseGroups
      .dispatchPreflightTag(
        builtInIncludesReleaseWriteAndCommit = false,
        wouldChange = IO.pure(true),
        runPreflight = _ => IO.pure(42)
      )
      .map(observed => assertEquals(observed, 42))
  }

  test("dispatchPreflightTag - propagates errors from wouldChange") {
    val boom = new RuntimeException("would-change boom")
    PreflightPhaseGroups
      .dispatchPreflightTag(
        builtInIncludesReleaseWriteAndCommit = true,
        wouldChange = IO.raiseError[Boolean](boom),
        runPreflight = _ => IO.pure("unused")
      )
      .attempt
      .map(result => assertEquals(result, Left(boom)))
  }

  test("dispatchPreflightTag - propagates errors from runPreflight") {
    val boom = new RuntimeException("preflight boom")
    PreflightPhaseGroups
      .dispatchPreflightTag(
        builtInIncludesReleaseWriteAndCommit = false,
        wouldChange = IO.pure(false),
        runPreflight = _ => IO.raiseError[String](boom)
      )
      .attempt
      .map(result => assertEquals(result, Left(boom)))
  }
}
