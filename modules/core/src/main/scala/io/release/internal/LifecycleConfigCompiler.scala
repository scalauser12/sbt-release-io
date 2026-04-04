package io.release.internal

import sbt.*

private[release] object LifecycleConfigCompiler {

  sealed trait Binding[Config] {
    def id: String
    def defaultSetting: Setting[?]
    def resolve(extracted: Extracted, config: Config): Config
    def merge(left: Config, right: Config): Config
    def isCustomized(config: Config): Boolean
  }

  final case class PolicyBinding[Config](
      id: String,
      key: SettingKey[Boolean],
      get: Config => Boolean,
      updated: (Config, Boolean) => Config
  ) extends Binding[Config] {
    override val defaultSetting: Setting[?] = key := true

    override def resolve(extracted: Extracted, config: Config): Config =
      updated(config, extracted.get(key))

    override def merge(left: Config, right: Config): Config =
      updated(left, get(left) && get(right))

    override def isCustomized(config: Config): Boolean =
      !get(config)
  }

  final case class HookBinding[Config, Hook](
      id: String,
      key: SettingKey[Seq[Hook]],
      get: Config => Seq[Hook],
      updated: (Config, Seq[Hook]) => Config
  ) extends Binding[Config] {
    override val defaultSetting: Setting[?] = key := Seq.empty[Hook]

    override def resolve(extracted: Extracted, config: Config): Config =
      updated(config, extracted.get(key))

    override def merge(left: Config, right: Config): Config =
      updated(left, get(left) ++ get(right))

    override def isCustomized(config: Config): Boolean =
      get(config).nonEmpty
  }

  sealed trait Slot[Config] {
    def id: String
    def keyLabel: String
    def binding: Binding[Config]
  }

  final case class PolicySlot[Config](
      binding: PolicyBinding[Config]
  ) extends Slot[Config] {
    override val id: String       = binding.id
    override val keyLabel: String = binding.key.key.label

    val enabled: Config => Boolean = binding.get
  }

  final case class HookSlot[Config, Hook](
      binding: HookBinding[Config, Hook]
  ) extends Slot[Config] {
    override val id: String       = binding.id
    override val keyLabel: String = binding.key.key.label

    val resolveHooks: Config => Seq[Hook] = binding.get

    def updated(config: Config, hooks: Seq[Hook]): Config =
      binding.updated(config, hooks)
  }

  def policyBinding[Config](
      id: String,
      key: SettingKey[Boolean],
      get: Config => Boolean,
      updated: (Config, Boolean) => Config
  ): PolicyBinding[Config] =
    PolicyBinding(
      id = id,
      key = key,
      get = get,
      updated = updated
    )

  def hookBinding[Config, Hook](
      id: String,
      key: SettingKey[Seq[Hook]],
      get: Config => Seq[Hook],
      updated: (Config, Seq[Hook]) => Config
  ): HookBinding[Config, Hook] =
    HookBinding(
      id = id,
      key = key,
      get = get,
      updated = updated
    )

  def policySlot[Config](
      key: SettingKey[Boolean],
      get: Config => Boolean,
      updated: (Config, Boolean) => Config
  ): PolicySlot[Config] =
    PolicySlot(
      policyBinding(
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
      hookBinding(
        id = key.key.label,
        key = key,
        get = get,
        updated = updated
      )
    )

  def configBindings[Config](
      slots: Seq[Slot[Config]]
  ): Seq[Binding[Config]] =
    slots.map(_.binding)

  def configBindings[Config](
      first: Slot[Config],
      rest: Slot[Config]*
  ): Seq[Binding[Config]] =
    first.binding +: rest.map(_.binding)

  def defaultSettings[Config, C, I](
      phases: Seq[LifecycleCompiler.Phase[Config, C, I]]
  ): Seq[Setting[?]] =
    uniqueBindings(phases).map(_.defaultSetting)

  def resolve[Config, C, I](
      state: State,
      empty: Config,
      phases: Seq[LifecycleCompiler.Phase[Config, C, I]]
  ): Config = {
    val extracted = SbtRuntime.extracted(state)

    uniqueBindings(phases).foldLeft(empty) { (config, binding) =>
      binding.resolve(extracted, config)
    }
  }

  def merge[Config, C, I](
      left: Config,
      right: Config,
      phases: Seq[LifecycleCompiler.Phase[Config, C, I]]
  ): Config =
    uniqueBindings(phases).foldLeft(left) { (config, binding) =>
      binding.merge(config, right)
    }

  def hasCustomizations[Config, C, I](
      config: Config,
      phases: Seq[LifecycleCompiler.Phase[Config, C, I]]
  ): Boolean =
    uniqueBindings(phases).exists(_.isCustomized(config))

  private def uniqueBindings[Config, C, I](
      phases: Seq[LifecycleCompiler.Phase[Config, C, I]]
  ): Seq[Binding[Config]] =
    phases
      .foldLeft((Vector.empty[Binding[Config]], Set.empty[String])) {
        case ((bindings, seen), phase) =>
          phase.configBindings.foldLeft((bindings, seen)) {
            case ((accBindings, accSeen), binding) if accSeen.contains(binding.id) =>
              accBindings -> accSeen
            case ((accBindings, accSeen), binding)                                 =>
              (accBindings :+ binding) -> (accSeen + binding.id)
          }
      }
      ._1
}
