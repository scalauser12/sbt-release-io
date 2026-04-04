package io.release.monorepo

import io.release.internal.LifecycleConfigCompiler

private[release] object MonorepoPolicySlots {

  val enableSnapshotDependenciesCheck
      : LifecycleConfigCompiler.PolicySlot[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  val enableRunClean: LifecycleConfigCompiler.PolicySlot[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  val enableRunTests: LifecycleConfigCompiler.PolicySlot[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  val enableTagging: LifecycleConfigCompiler.PolicySlot[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  val enablePublish: LifecycleConfigCompiler.PolicySlot[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  val enablePush: LifecycleConfigCompiler.PolicySlot[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policySlot(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush,
      get = _.enablePush,
      updated = (config, value) => config.copy(enablePush = value)
    )

  val policySlots: Vector[LifecycleConfigCompiler.PolicySlot[MonorepoHookConfiguration]] = Vector(
    enableSnapshotDependenciesCheck,
    enableRunClean,
    enableRunTests,
    enableTagging,
    enablePublish,
    enablePush
  )
}
