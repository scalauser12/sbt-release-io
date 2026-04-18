package io.release.runtime

/** Canonical hook-phase names shared by core and monorepo lifecycles and their preflights.
  *
  * Names are also used as step-name prefixes (e.g. `"after-clean-check:my-hook"`) and as the
  * filter keys preflight uses to decide whether runtime hook execution can still mutate
  * gating state. Renaming a phase here updates lifecycle, preflight, and process-plan call
  * sites in lockstep.
  */
private[release] object HookPhases {
  val AfterCleanCheck: String           = "after-clean-check"
  val BeforeSelection: String           = "before-selection"
  val AfterSelection: String            = "after-selection"
  val BeforeVersionResolution: String   = "before-version-resolution"
  val AfterVersionResolution: String    = "after-version-resolution"
  val BeforeReleaseVersionWrite: String = "before-release-version-write"
  val AfterReleaseVersionWrite: String  = "after-release-version-write"
  val BeforeReleaseCommit: String       = "before-release-commit"
  val AfterReleaseCommit: String        = "after-release-commit"
  val BeforeTag: String                 = "before-tag"
  val AfterTag: String                  = "after-tag"
  val BeforePublish: String             = "before-publish"
  val AfterPublish: String              = "after-publish"
  val BeforeNextVersionWrite: String    = "before-next-version-write"
  val AfterNextVersionWrite: String     = "after-next-version-write"
  val BeforeNextCommit: String          = "before-next-commit"
  val AfterNextCommit: String           = "after-next-commit"
  val BeforePush: String                = "before-push"
  val AfterPush: String                 = "after-push"
}
