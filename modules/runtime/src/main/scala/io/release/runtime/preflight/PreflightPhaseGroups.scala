package io.release.runtime.preflight

import cats.effect.IO
import io.release.runtime.HookPhases
import io.release.vcs.TagConflictResolver
import io.release.vcs.Vcs

/** Shared preflight phase classifications used by both core and monorepo preflight.
  *
  * The `versionResolutionBlockingPhases` upper layer differs per plugin (core's two-phase
  * variant vs. monorepo's selection-aware four-phase variant), so each module supplies
  * its own and uses [[tagAffectingPhases]] to combine. The post-version-resolution phase
  * sets are intent-identical across modules and live here as the single source of truth.
  */
private[release] object PreflightPhaseGroups {

  /** Phases from after-version-resolution through before-tag that can reshape the would-be
    * release commit inspected by the built-in tag preflight.
    */
  val TagPreflightRelevantPhases: Set[String] = Set(
    HookPhases.AfterVersionResolution,
    HookPhases.BeforeReleaseVersionWrite,
    HookPhases.AfterReleaseVersionWrite,
    HookPhases.BeforeReleaseCommit,
    HookPhases.AfterReleaseCommit,
    HookPhases.BeforeTag
  )

  /** Phases after `before-tag` that can still mutate the version summary. */
  val PostTagVersionMutationPhases: Set[String] = Set(
    HookPhases.AfterTag,
    HookPhases.BeforePublish,
    HookPhases.AfterPublish,
    HookPhases.BeforeNextVersionWrite,
    HookPhases.AfterNextVersionWrite,
    HookPhases.BeforeNextCommit
  )

  /** All phases that can mutate the version summary, regardless of position relative to
    * the tag step. Convenience union of [[TagPreflightRelevantPhases]] and
    * [[PostTagVersionMutationPhases]].
    */
  val VersionSummaryMutationPhases: Set[String] =
    TagPreflightRelevantPhases ++ PostTagVersionMutationPhases

  /** Built-in tag preflight is invalidated by any phase that can shape the release commit
    * through `before-tag`. After-tag and later phases cannot retroactively change the tag
    * preflight result and are intentionally excluded.
    */
  def tagAffectingPhases(versionResolutionBlockingPhases: Set[String]): Set[String] =
    versionResolutionBlockingPhases ++ TagPreflightRelevantPhases

  /** Built-in tag preflight should run iff tagging is enabled and no hook in any
    * tag-preflight-relevant phase has opted out via `mayChangeTagSettings`. Each
    * boolean in `hookGroupsMayChange` reports whether the corresponding hook list
    * (typically the five phases in [[TagPreflightRelevantPhases]] minus the
    * runtime-installed `AfterVersionResolution` step) contains an opt-in hook.
    */
  def tagPreflightEnabled(enableTagging: Boolean, hookGroupsMayChange: Boolean*): Boolean =
    enableTagging && !hookGroupsMayChange.contains(true)

  /** Returns `true` if any compiled step name matches one of the given hook
    * `phases` (matching the lifecycle compiler's `"$phase:$hookName"` format).
    * Used by core/monorepo preflight to detect when a runtime hook may still
    * mutate state observed by `check` mode summaries.
    */
  def hasHookPhase(stepNames: Seq[String], phase: String): Boolean =
    stepNames.exists(_.startsWith(s"$phase:"))

  /** Convenience: returns `true` if any compiled step name matches any of the
    * supplied hook `phases`.
    */
  def anyPhasePresent(stepNames: Seq[String], phases: Set[String]): Boolean =
    phases.exists(p => hasHookPhase(stepNames, p))

  /** Run the appropriate preflight call given whether the built-in preflight
    * already covers the release-write-and-commit pair.
    *
    * When the built-in pair is in the plan AND the release write would mutate
    * the version file, the underlying preflight runs with a callback that pins
    * the expected commit to [[TagConflictResolver.PreflightCommitTarget.FutureReleaseCommit]]
    * — telling the conflict resolver "the tag will land on a NEW commit." When
    * the pair isn't in the plan or the release write is a no-op (version file
    * already matches), the preflight runs with its default commit-target callback
    * (`vcs.currentHash` → `ExactCommit`).
    */
  def dispatchPreflightTag[A](
      builtInIncludesReleaseWriteAndCommit: Boolean,
      wouldChange: => IO[Boolean],
      runPreflight: Option[Vcs => IO[TagConflictResolver.PreflightCommitTarget]] => IO[A]
  ): IO[A] =
    if (!builtInIncludesReleaseWriteAndCommit) runPreflight(None)
    else
      wouldChange.flatMap {
        case true  =>
          runPreflight(
            Some(_ => IO.pure(TagConflictResolver.PreflightCommitTarget.FutureReleaseCommit))
          )
        case false => runPreflight(None)
      }
}
