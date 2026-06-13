package io.release.runtime

/** The six release-process policy flags shared by the core and monorepo hook configurations.
  *
  * Owns the conjunction semantics ([[mergeWith]]) so both modules merge layered policy
  * settings identically. The configurations keep these as flat fields and convert to/from this
  * carrier rather than embedding it, preserving their case-class arity.
  */
private[release] final case class PolicyFlags(
    enableSnapshotDependenciesCheck: Boolean = true,
    enableRunClean: Boolean = true,
    enableRunTests: Boolean = true,
    enableTagging: Boolean = true,
    enablePublish: Boolean = true,
    enablePush: Boolean = true
) {

  /** Conjunction merge: a phase stays enabled only when both sides enable it. */
  def mergeWith(other: PolicyFlags): PolicyFlags =
    PolicyFlags(
      enableSnapshotDependenciesCheck =
        enableSnapshotDependenciesCheck && other.enableSnapshotDependenciesCheck,
      enableRunClean = enableRunClean && other.enableRunClean,
      enableRunTests = enableRunTests && other.enableRunTests,
      enableTagging = enableTagging && other.enableTagging,
      enablePublish = enablePublish && other.enablePublish,
      enablePush = enablePush && other.enablePush
    )
}
