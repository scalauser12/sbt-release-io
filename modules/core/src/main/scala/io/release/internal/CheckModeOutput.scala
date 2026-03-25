package io.release.internal

/** Shared wording and summary helpers for `check` mode across both plugins. */
private[release] object CheckModeOutput {

  val NoReleaseSideEffects: String =
    "No release side effects: no version-file writes, commits, tags, publish, or push"

  val CheckModeLogSummary: String =
    "Check mode has no release side effects: no version-file writes, commits, tags, publish, or push"

  val CrossBuildValidationNote: String =
    "With cross-build validation enabled, sbt may temporarily switch " +
      "Scala versions during validation and then restore the entry version"

  val PushConfiguredSummary: String =
    "configured (not executed in check mode)"

  def publishStatus(
      publishConfigured: Boolean,
      skipPublish: Boolean,
      skippedMessage: String
  ): String =
    if (!publishConfigured) "step not configured"
    else if (skipPublish) skippedMessage
    else "enabled"

  def pushStatus(pushConfigured: Boolean): String =
    if (pushConfigured) PushConfiguredSummary
    else "step not configured"

  def enabled(flag: Boolean): String =
    if (flag) "enabled" else "disabled"
}
