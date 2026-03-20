package io.release.internal

import cats.effect.IO

/** Shared publish-target validation used by core and monorepo publish steps. */
private[release] object PublishValidation {

  private val Hint = "Set publishTo or add `publish / skip := true`."

  def message(projectLabels: String): String =
    s"publishTo not configured for: $projectLabels. $Hint"

  /** When `Some`, publishing must not proceed (same text as [[message]]). */
  def publishTargetError(projectLabels: String)(
      publishSkipped: Boolean,
      publishToEmpty: Boolean
  ): Option[String] =
    if (!publishSkipped && publishToEmpty) Some(message(projectLabels))
    else None

  /** When publish is not skipped, fail if there is no resolver (`publishTo` empty). */
  def requirePublishTarget(projectLabels: String)(
      publishSkipped: Boolean,
      publishToEmpty: Boolean
  ): IO[Unit] =
    publishTargetError(projectLabels)(publishSkipped, publishToEmpty) match {
      case None        => IO.unit
      case Some(error) => IO.raiseError(new IllegalStateException(error))
    }
}
