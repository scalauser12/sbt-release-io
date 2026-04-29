package io.release

import _root_.io.release.runtime.sbt.SbtRuntime
import _root_.sbt.{internal as _, *}

/** Public surface for hook authors that need to mutate sbt session state in
  * ways that survive across release steps.
  *
  * Hooks that install settings the next step must observe (e.g. a
  * `before-publish` hook installing `publish/skip := true`, or a
  * `before-version-resolution` hook installing late-bound
  * `releaseIOMonorepoVersioningFile` mappings) should use
  * [[appendSessionSettings]] instead of `Extracted.appendWithSession`.
  *
  * `Extracted.appendWithSession` reapplies `session.mergeSettings ++ newSettings`
  * to the structure but stores the unchanged session back into state. The next
  * `appendWithSession` call rebuilds structure from `mergeSettings` (which
  * does not include the prior call's settings) and silently drops them.
  * `appendSessionSettings` installs settings into `session.rawAppend`, which
  * is part of `mergeSettings`, so they persist across every subsequent
  * structure rebuild.
  *
  * For genuinely transient evaluation that should not propagate, keep using
  * `Extracted.appendWithSession`.
  */
object ReleaseSessionOps {

  /** Install settings into `session.rawAppend` so they survive subsequent
    * `appendWithSession` calls. See class-level docs for when to use this
    * vs. `Extracted.appendWithSession`.
    */
  def appendSessionSettings(state: State, settings: Seq[Setting[?]]): State =
    SbtRuntime.appendSessionSettings(state, settings)
}
