package io.release

import sbt.{internal as _, *}

/** Internal sbt-version compatibility shim shared across the core and monorepo modules.
  * This is public for cross-module reuse and is not a supported end-user extension point.
  *
  * Both `runBuild` and `runProject` use project-scoped `clean` via `runAggregated` so that
  * multi-project builds only clean the targeted subtree, not the entire build.
  */
object CleanCompat:

  def runBuild(state: State, ref: ProjectRef): State =
    val extracted = Project.extract(state)
    extracted.runAggregated(ref / (Global / Keys.clean), state)

  def runProject(state: State, ref: ProjectRef): State =
    val extracted = Project.extract(state)
    extracted.runAggregated(ref / (Global / Keys.clean), state)
