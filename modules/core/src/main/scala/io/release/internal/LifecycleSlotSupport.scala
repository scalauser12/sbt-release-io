package io.release.internal

import sbt.SettingKey

private[release] object LifecycleSlotSupport {

  sealed trait Slot[Config] {
    def id: String
    def keyLabel: String
    def binding: LifecycleConfigCompiler.Binding[Config]
  }

  final case class PolicySlot[Config](
      binding: LifecycleConfigCompiler.PolicyBinding[Config]
  ) extends Slot[Config] {
    override val id: String       = binding.id
    override val keyLabel: String = binding.key.key.label

    val enabled: Config => Boolean = binding.get
  }

  final case class HookSlot[Config, Hook](
      binding: LifecycleConfigCompiler.HookBinding[Config, Hook]
  ) extends Slot[Config] {
    override val id: String       = binding.id
    override val keyLabel: String = binding.key.key.label

    val resolveHooks: Config => Seq[Hook] = binding.get
  }

  def policySlot[Config](
      key: SettingKey[Boolean],
      get: Config => Boolean,
      updated: (Config, Boolean) => Config
  ): PolicySlot[Config] =
    PolicySlot(
      LifecycleConfigCompiler.policyBinding(
        id = key.key.label,
        key = key,
        get = get,
        updated = updated
      )
    )

  def hookSlot[Config, Hook](
      key: SettingKey[Seq[Hook]],
      get: Config => Seq[Hook],
      updated: (Config, Seq[Hook]) => Config
  ): HookSlot[Config, Hook] =
    HookSlot(
      LifecycleConfigCompiler.hookBinding(
        id = key.key.label,
        key = key,
        get = get,
        updated = updated
      )
    )

  def configBindings[Config](
      slots: Seq[Slot[Config]]
  ): Seq[LifecycleConfigCompiler.Binding[Config]] =
    slots.map(_.binding)

  def configBindings[Config](
      first: Slot[Config],
      rest: Slot[Config]*
  ): Seq[LifecycleConfigCompiler.Binding[Config]] =
    first.binding +: rest.map(_.binding)
}
