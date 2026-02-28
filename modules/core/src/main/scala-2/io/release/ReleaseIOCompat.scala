package io.release

import sbt.*

/** sbt 1 compatibility shim for task keys that differ between sbt 1 and sbt 2. */
private[release] object ReleaseIOCompat {
  def testKey: TaskKey[Unit]  = sbt.Keys.test
  def cleanKey: TaskKey[Unit] = sbt.Keys.clean
}
