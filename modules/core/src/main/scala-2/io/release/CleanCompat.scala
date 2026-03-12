package io.release

import sbt.{internal => _, *}
import sbt.Keys.clean

/** Internal sbt-version compatibility shim shared across the core and monorepo modules.
  * This is public for cross-module reuse and is not a supported end-user extension point.
  */
object CleanCompat {

  def runBuild(state: State, ref: ProjectRef): State = {
    val extracted = Project.extract(state)
    extracted.runAggregated(ref / (Global / clean), state)
  }

  def runProject(state: State, ref: ProjectRef): State = {
    val extracted = Project.extract(state)
    extracted.runAggregated(ref / (Global / clean), state)
  }
}
