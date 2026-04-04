package io.release.internal

private[release] object CoreLifecycleSlots {

  val policySlots: Vector[LifecycleConfigCompiler.PolicyBinding[CoreHookConfiguration]] =
    CorePolicySlots.policySlots

  val hookSlots: Vector[
    LifecycleConfigCompiler.HookBinding[CoreHookConfiguration, io.release.ReleaseHookIO]
  ] =
    CoreHookSlots.hookSlots

  val slots: Vector[LifecycleConfigCompiler.Binding[CoreHookConfiguration]] =
    policySlots ++ hookSlots
}
