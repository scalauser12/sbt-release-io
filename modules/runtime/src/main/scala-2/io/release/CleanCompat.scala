package io.release

import _root_.sbt.Keys.clean
import _root_.sbt.{internal as _, *}

/** Internal sbt-version compatibility shim shared across the core and monorepo modules.
  * This is public for cross-module reuse and is not a supported end-user extension point.
  */
object CleanCompat {

  def runBuild(state: State, ref: ProjectRef): State = {
    val extracted = Project.extract(state)
    extracted.runAggregated(ref / (Global / clean), state)
  }
}
