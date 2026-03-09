package io.release

import sbt.*

/** Internal sbt-version compatibility shim shared across the core and monorepo modules.
  * This is public for cross-module reuse and is not a supported end-user extension point.
  *
  * In sbt 2, `test` becomes an InputKey, replaced by `testFull` as a `TaskKey[TestResult]`.
  */
object ReleaseIOCompat:
  def testKey: TaskKey[sbt.protocol.testing.TestResult] = sbt.Keys.testFull
