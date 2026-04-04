package io.release.monorepo

import io.release.internal.LifecycleConfigCompiler

private[release] object MonorepoLifecycleSlots {

  val policySlots: Vector[LifecycleConfigCompiler.PolicySlot[MonorepoHookConfiguration]] =
    MonorepoPolicySlots.policySlots

  val globalHookSlots
      : Vector[LifecycleConfigCompiler.HookSlot[MonorepoHookConfiguration, MonorepoGlobalHookIO]] =
    MonorepoGlobalHookSlots.globalHookSlots

  val projectHookSlots: Vector[
    LifecycleConfigCompiler.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO]
  ] =
    MonorepoProjectHookSlots.projectHookSlots

  val slots: Vector[LifecycleConfigCompiler.Slot[MonorepoHookConfiguration]] =
    policySlots ++ globalHookSlots ++ projectHookSlots
}
