package io.release

import sbt.{internal as _, *}

/** Internal sbt-version compatibility shim shared across the core and monorepo modules.
  * This is public for cross-module reuse and is not a supported end-user extension point.
  */
object ReleaseIOCompat {
  def testKey: TaskKey[Unit] = sbt.Keys.test

  def uncached[A](body: => A): A = body

  def snapshotDependenciesFromManagedClasspath(
      classpath: Seq[Attributed[_]]
  ): Seq[ModuleID] =
    classpath
      .flatMap(_.get(Keys.moduleID.key))
      .filter(m => m.isChanging || m.revision.endsWith("-SNAPSHOT"))

  /** Snapshot dependency setting initializer.
    * Checks resolved library dependencies from the Test classpath (which includes Runtime),
    * not inter-project dependencies (projectDependencies) — those are resolved internally
    * by sbt from compiled classes and don't need a snapshot check.
    * In sbt 1, Attributed supports typed AttributeKey[ModuleID] via moduleID.key.
    */
  def snapshotDependenciesSetting: Setting[?] =
    ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies := {
      snapshotDependenciesFromManagedClasspath((Test / Keys.managedClasspath).value)
    }
}
