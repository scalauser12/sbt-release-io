package io.release

import sbt.{internal as _, *}

/** Thin wrapper over `Project.extract`. Lives in the shared source root because the body has no
  * sbt-version-specific construct (unlike the genuinely split `ReleaseIOTestkitSbtBridge`).
  */
object TestkitSbtCompat {

  def extract(state: State): Extracted =
    Project.extract(state)
}
