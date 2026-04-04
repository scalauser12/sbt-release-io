package io.release.monorepo

import io.release.internal.PublicKeyCatalogSupport.PublicEntry
private[release] object MonorepoPublicKeyCatalog {

  private val selectionEntries: Vector[PublicEntry]  = MonorepoSelectionPublicKeys.publicEntries
  private val behaviorEntries: Vector[PublicEntry]   = MonorepoBehaviorPublicKeys.publicEntries
  private val policyEntries: Vector[PublicEntry]     = MonorepoPolicyPublicKeys.publicEntries
  private val hookEntries: Vector[PublicEntry]       = MonorepoHookPublicKeys.publicEntries
  private val versioningEntries: Vector[PublicEntry] =
    MonorepoVersioningPublicKeys.publicEntries
  private val detectionEntries: Vector[PublicEntry]  = MonorepoDetectionPublicKeys.publicEntries
  private val vcsEntries: Vector[PublicEntry]        = MonorepoVcsPublicKeys.publicEntries
  private val publishEntries: Vector[PublicEntry]    = MonorepoPublishPublicKeys.publicEntries

  val publicEntries: Vector[PublicEntry] =
    selectionEntries ++
      behaviorEntries ++
      policyEntries ++
      hookEntries ++
      versioningEntries ++
      detectionEntries ++
      vcsEntries ++
      publishEntries
}
