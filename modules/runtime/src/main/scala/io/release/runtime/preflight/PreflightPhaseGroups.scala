package io.release.runtime.preflight

import io.release.runtime.HookPhases

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
}
