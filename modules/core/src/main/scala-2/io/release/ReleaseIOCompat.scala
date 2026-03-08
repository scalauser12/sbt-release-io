package io.release

import sbt.*

/** Internal sbt-version compatibility shim shared across the core and monorepo modules.
  * This is public for cross-module reuse and is not a supported end-user extension point.
  */
object ReleaseIOCompat {
  def testKey: TaskKey[Unit]  = sbt.Keys.test
  def cleanKey: TaskKey[Unit] = sbt.Keys.clean
}
