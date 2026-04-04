package io.release.internal

import io.release.ReleaseIO

private[release] object CorePolicySlots {

  val enableSnapshotDependenciesCheck: LifecycleConfigCompiler.PolicySlot[CoreHookConfiguration] =
    LifecycleConfigCompiler.policySlot(
      key = ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  val enableRunClean: LifecycleConfigCompiler.PolicySlot[CoreHookConfiguration] =
    LifecycleConfigCompiler.policySlot(
      key = ReleaseIO.releaseIOPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  val enableRunTests: LifecycleConfigCompiler.PolicySlot[CoreHookConfiguration] =
    LifecycleConfigCompiler.policySlot(
      key = ReleaseIO.releaseIOPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  val enableTagging: LifecycleConfigCompiler.PolicySlot[CoreHookConfiguration] =
    LifecycleConfigCompiler.policySlot(
      key = ReleaseIO.releaseIOPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  val enablePublish: LifecycleConfigCompiler.PolicySlot[CoreHookConfiguration] =
    LifecycleConfigCompiler.policySlot(
      key = ReleaseIO.releaseIOPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  val enablePush: LifecycleConfigCompiler.PolicySlot[CoreHookConfiguration] =
    LifecycleConfigCompiler.policySlot(
      key = ReleaseIO.releaseIOPolicyEnablePush,
      get = _.enablePush,
      updated = (config, value) => config.copy(enablePush = value)
    )

  val policySlots: Vector[LifecycleConfigCompiler.PolicySlot[CoreHookConfiguration]] = Vector(
    enableSnapshotDependenciesCheck,
    enableRunClean,
    enableRunTests,
    enableTagging,
    enablePublish,
    enablePush
  )
}
