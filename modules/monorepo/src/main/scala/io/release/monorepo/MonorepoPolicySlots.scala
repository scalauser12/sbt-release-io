package io.release.monorepo

import io.release.internal.LifecycleConfigCompiler

private[release] object MonorepoPolicySlots {

  val enableSnapshotDependenciesCheck
      : LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck,
      get = _.enableSnapshotDependenciesCheck,
      updated = (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    )

  val enableRunClean: LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean,
      get = _.enableRunClean,
      updated = (config, value) => config.copy(enableRunClean = value)
    )

  val enableRunTests: LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests,
      get = _.enableRunTests,
      updated = (config, value) => config.copy(enableRunTests = value)
    )

  val enableTagging: LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging,
      get = _.enableTagging,
      updated = (config, value) => config.copy(enableTagging = value)
    )

  val enablePublish: LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish,
      get = _.enablePublish,
      updated = (config, value) => config.copy(enablePublish = value)
    )

  val enablePush: LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration] =
    LifecycleConfigCompiler.policyBinding(
      key = MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush,
      get = _.enablePush,
      updated = (config, value) => config.copy(enablePush = value)
    )

  val policySlots: Vector[LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration]] =
    Vector(
      enableSnapshotDependenciesCheck,
      enableRunClean,
      enableRunTests,
      enableTagging,
      enablePublish,
      enablePush
    )
}
