package io.release

import sbt.{internal => _, *}

/** Internal sbt-version compatibility shim shared across the core and monorepo modules.
  * This is public for cross-module reuse and is not a supported end-user extension point.
  *
  * In sbt 2, `test` becomes an InputKey, replaced by `testFull` as a `TaskKey[TestResult]`.
  */
object ReleaseIOCompat:
  def testKey: TaskKey[sbt.protocol.testing.TestResult] = sbt.Keys.testFull

  /** Snapshot dependency setting initializer.
    * Uses `update.value.allModules` instead of `managedClasspath` because sbt 2's
    * `Attributed` uses `StringAttributeKey` only — typed `AttributeKey[ModuleID]` is unsupported.
    * Wrapped in Def.uncached because sbt 2 requires JsonFormat for cached tasks.
    */
  def snapshotDependenciesSetting: Setting[?] =
    ReleaseIO._releaseIOSnapshotDependencies := Def.uncached {
      val projDeps = Keys.projectDependencies.value
      val resolved = (Runtime / Keys.update).value.allModules
      (projDeps ++ resolved).filter(m => m.isChanging || m.revision.endsWith("-SNAPSHOT"))
    }
