package io.release

import sbt.*

/** sbt 2 compatibility shim for task keys that differ between sbt 1 and sbt 2.
  * In sbt 2, `test` becomes an InputKey, replaced by `testFull` as a TaskKey.
  * `clean` remains a TaskKey[Unit] in both versions.
  */
private[release] object ReleaseIOCompat {
  def testKey: TaskKey[?]     = sbt.Keys.testFull
  def cleanKey: TaskKey[Unit] = sbt.Keys.clean
}
