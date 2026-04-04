package io.release.internal

private[release] object CoreLifecycleSlots {

  val policySlots: Vector[LifecycleConfigCompiler.PolicySlot[CoreHookConfiguration]] =
    CorePolicySlots.policySlots

  val hookSlots
      : Vector[LifecycleConfigCompiler.HookSlot[CoreHookConfiguration, io.release.ReleaseHookIO]] =
    CoreHookSlots.hookSlots

  val slots: Vector[LifecycleConfigCompiler.Slot[CoreHookConfiguration]] =
    policySlots ++ hookSlots
}
