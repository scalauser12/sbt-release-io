package io.release

import sbt.AttributeKey

/** Attribute keys for runtime state stored in `sbt.State` during a release.
  *
  * Populated by release steps at runtime; command-line flags live in
  * [[internal.ExecutionFlags]] and plan-specific overrides in [[internal.CoreReleasePlan]].
  */
private[release] object ReleaseKeys {

  /** Stored on `sbt.State` so that the version pair is available to sbt settings
    * (e.g. `releaseIOTagName`) that cannot read from [[ReleaseContext]] directly.
    */
  val versions: AttributeKey[(String, String)] =
    AttributeKey[(String, String)]("releaseIOVersions", "Release and next version pair")

  val runtimeVersionOverride: AttributeKey[String] =
    AttributeKey[String](
      "releaseIORuntimeVersionOverride",
      "Version set by setReleaseVersion/setNextVersion, read by tag/commit message tasks"
    )
}
