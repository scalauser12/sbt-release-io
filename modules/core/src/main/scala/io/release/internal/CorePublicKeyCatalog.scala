package io.release.internal

private[release] object CorePublicKeyCatalog {
  import PublicKeyCatalogSupport.PublicEntry

  private val behaviorEntries: Vector[PublicEntry]    = CoreBehaviorPublicKeys.publicEntries
  private val defaultsEntries: Vector[PublicEntry]    = CoreDefaultsPublicKeys.publicEntries
  private val policyEntries: Vector[PublicEntry]      = CorePolicyPublicKeys.publicEntries
  private val hookEntries: Vector[PublicEntry]        = CoreHookPublicKeys.publicEntries
  private val versioningEntries: Vector[PublicEntry]  = CoreVersioningPublicKeys.publicEntries
  private val vcsEntries: Vector[PublicEntry]         = CoreVcsPublicKeys.publicEntries
  private val publishEntries: Vector[PublicEntry]     = CorePublishPublicKeys.publicEntries
  private val runtimeEntries: Vector[PublicEntry]     = CoreRuntimePublicKeys.publicEntries
  private val diagnosticsEntries: Vector[PublicEntry] = CoreDiagnosticsPublicKeys.publicEntries

  val publicEntries: Vector[PublicEntry] =
    behaviorEntries ++
      defaultsEntries ++
      policyEntries ++
      hookEntries ++
      versioningEntries ++
      vcsEntries ++
      publishEntries ++
      runtimeEntries ++
      diagnosticsEntries
}
