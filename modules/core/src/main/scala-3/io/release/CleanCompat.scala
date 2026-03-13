package io.release

import _root_.io.release.internal.SbtRuntime
import sbt.{internal as _, *}

/** Internal sbt-version compatibility shim shared across the core and monorepo modules.
  * This is public for cross-module reuse and is not a supported end-user extension point.
  *
  * In sbt 2, the single-project core plugin uses the upstream `cleanFull` command. The monorepo
  * plugin keeps using the project-scoped `clean` task because `cleanFull` is build-wide and would
  * wipe outputs for untouched projects.
  */
object CleanCompat:

  def runBuild(state: State, ref: ProjectRef): State =
    SbtRuntime.runCommandAndRemaining(state, BasicCommandStrings.CleanFull)

  def runProject(state: State, ref: ProjectRef): State =
    val extracted = Project.extract(state)
    extracted.runAggregated(ref / (Global / Keys.clean), state)
