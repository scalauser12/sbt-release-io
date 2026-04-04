package io.release.internal

import sbt.ModuleID

import PublicKeyCatalogSupport.PublicEntry
import PublicKeyCatalogSupport.task

@scala.annotation.nowarn("cat=deprecation")
private[release] object CoreDiagnosticsPublicKeys {

  private val releaseIODiagnosticsSnapshotDependenciesDef = task[Seq[ModuleID]](
    group = "diagnostics",
    label = "releaseIODiagnosticsSnapshotDependencies",
    description = "Task that resolves SNAPSHOT dependencies for validation",
    isTransient = true
  )
  val releaseIODiagnosticsSnapshotDependencies            =
    releaseIODiagnosticsSnapshotDependenciesDef.key

  val publicEntries: Vector[PublicEntry] = Vector(
    releaseIODiagnosticsSnapshotDependenciesDef.publicEntry
  )
}
