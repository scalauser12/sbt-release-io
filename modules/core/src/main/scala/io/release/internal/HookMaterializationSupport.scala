package io.release.internal

private[release] object HookMaterializationSupport {

  type HookAssignment[Config, Hook] = (LifecycleSlotSupport.HookSlot[Config, Hook], Seq[Hook])

  def materializedAssignments[Config, ResourceHook, Hook](
      slots: Seq[LifecycleSlotSupport.HookSlot[Config, Hook]],
      hookBuckets: Seq[Seq[ResourceHook]]
  )(
      materialize: ResourceHook => Hook
  ): Seq[HookAssignment[Config, Hook]] = {
    require(
      slots.length == hookBuckets.length,
      s"Expected ${slots.length} hook buckets but received ${hookBuckets.length}"
    )

    slots.zip(hookBuckets).map { case (slot, hooks) => slot -> hooks.map(materialize) }
  }

  def applyAssignments[Config, Hook](
      seed: Config,
      assignments: Seq[HookAssignment[Config, Hook]]
  ): Config =
    assignments.foldLeft(seed) { case (config, (slot, hooks)) =>
      slot.binding.updated(config, hooks)
    }
}
