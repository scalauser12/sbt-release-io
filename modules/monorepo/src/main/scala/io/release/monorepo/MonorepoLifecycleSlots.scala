package io.release.monorepo

import io.release.internal.LifecycleConfigCompiler

private[release] object MonorepoLifecycleSlots {

  val policySlots: Vector[LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration]] =
    MonorepoPolicySlots.policySlots

  val globalHookSlots: Vector[
    LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO]
  ] =
    MonorepoGlobalHookSlots.globalHookSlots

  val projectHookSlots: Vector[
    LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO]
  ] =
    MonorepoProjectHookSlots.projectHookSlots

  val slots: Vector[LifecycleConfigCompiler.Binding[MonorepoHookConfiguration]] =
    policySlots ++ globalHookSlots ++ projectHookSlots
}
