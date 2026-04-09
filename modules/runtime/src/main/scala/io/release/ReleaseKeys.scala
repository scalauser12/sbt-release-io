package io.release

import _root_.sbt.AttributeKey

/** Attribute keys for runtime state stored in `_root_.sbt.State` during a release.
  *
  * Populated by release steps at runtime; command-line flags live in
  * [[io.release.runtime.ExecutionFlags]] and plan-specific overrides in the
  * module-specific release plans.
  */
private[release] object ReleaseKeys {

  /** Stored on `_root_.sbt.State` so that the version pair is available to sbt settings
    * (e.g. `releaseIOVcsTagName`) that cannot read from [[ReleaseContext]] directly.
    */
  val versions: AttributeKey[(String, String)] =
    AttributeKey[(String, String)]("releaseIOVersions", "Release and next version pair")
}
