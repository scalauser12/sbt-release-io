package io.release

import io.release.version.Version
import sbt.{internal as _, *}

/** Internal sbt-version compatibility shim shared across the core and monorepo modules.
  * This is public for cross-module reuse and is not a supported end-user extension point.
  *
  * In sbt 2, `test` becomes an InputKey, replaced by `testFull` as a `TaskKey[TestResult]`.
  */
object ReleaseIOCompat:
  def testKey: TaskKey[sbt.protocol.testing.TestResult] = sbt.Keys.testFull

  def snapshotDependenciesFromManagedClasspath(
      classpath: Seq[Attributed[?]]
  ): Seq[ModuleID] =
    classpath
      .flatMap(_.get(Keys.moduleIDStr))
      .map(sbt.Classpaths.moduleIdJsonKeyFormat.read)
      .filter(m => m.isChanging || m.revision.endsWith("-SNAPSHOT"))

  /** Snapshot dependency setting initializer.
    * Checks resolved library dependencies from the Test classpath (which includes Runtime) —
    * inter-project dependencies (via `.dependsOn()`) are resolved internally by sbt and excluded.
    * In sbt 2, `Attributed` uses `StringAttributeKey` only, so we read `moduleIDStr`
    * and deserialize via `Classpaths.moduleIdJsonKeyFormat` (same approach as sbt-release).
    */
  def snapshotDependenciesSetting: Setting[?] =
    ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies := {
      snapshotDependenciesFromManagedClasspath((Test / Keys.managedClasspath).value)
    }

  /** ThisBuild-scoped default for `releaseIOVersioningBump`.
    *
    * sbt 2 caches `ThisBuild`-scoped task results and would otherwise demand a
    * `JsonFormat[Version.Bump]`; the value is a case-object selector with no I/O,
    * so caching adds no benefit. The wrapping must be a literal `Def.uncached`
    * at the `:=` body for the sbt 2 macro to recognize it, so the helper lives
    * here in the scala-3 source root rather than in shared code.
    */
  def buildScopedVersioningBumpDefault: Setting[?] =
    ThisBuild / ReleaseSharedKeys.releaseIOVersioningBump := Def.uncached(Version.Bump.default)
