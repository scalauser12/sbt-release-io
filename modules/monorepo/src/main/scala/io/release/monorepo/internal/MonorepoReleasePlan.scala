package io.release.monorepo.internal

import io.release.internal.ExecutionFlags

/** How project selection is determined for a monorepo release. */
private[monorepo] sealed trait SelectionMode

private[monorepo] object SelectionMode {
  case object ExplicitSelection extends SelectionMode
  case object AllChanged        extends SelectionMode
  case object DetectChanges     extends SelectionMode
}

/** Stable startup inputs captured once at monorepo command start. */
private[monorepo] final case class MonorepoReleasePlan(
    flags: ExecutionFlags,
    selectionMode: SelectionMode,
    selectedNames: Seq[String],
    releaseVersionOverrides: Map[String, String],
    nextVersionOverrides: Map[String, String],
    globalReleaseVersion: Option[String],
    globalNextVersion: Option[String]
)
