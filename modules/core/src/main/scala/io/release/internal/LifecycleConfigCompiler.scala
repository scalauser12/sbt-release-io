package io.release.internal

import sbt.*

private[release] object LifecycleConfigCompiler {

  sealed trait Binding[Config] {
    def id: String
    def keyLabel: String
    def defaultSetting: Setting[?]
    def resolve(extracted: Extracted, config: Config): Config
    def merge(left: Config, right: Config): Config
    def isCustomized(config: Config): Boolean
  }

  final case class PolicyBinding[Config](
      key: SettingKey[Boolean],
      get: Config => Boolean,
      updated: (Config, Boolean) => Config
  ) extends Binding[Config] {
    override val id: String                 = key.key.label
    override val keyLabel: String           = key.key.label
    override val defaultSetting: Setting[?] = key := true

    val enabled: Config => Boolean = get

    override def resolve(extracted: Extracted, config: Config): Config =
      updated(config, extracted.get(key))

    override def merge(left: Config, right: Config): Config =
      updated(left, get(left) && get(right))

    override def isCustomized(config: Config): Boolean =
      !get(config)
  }

  final case class HookBinding[Config, Hook](
      key: SettingKey[Seq[Hook]],
      get: Config => Seq[Hook],
      updated: (Config, Seq[Hook]) => Config
  ) extends Binding[Config] {
    override val id: String                 = key.key.label
    override val keyLabel: String           = key.key.label
    override val defaultSetting: Setting[?] = key := Seq.empty[Hook]

    val resolveHooks: Config => Seq[Hook] = get

    override def resolve(extracted: Extracted, config: Config): Config =
      updated(config, extracted.get(key))

    override def merge(left: Config, right: Config): Config =
      updated(left, get(left) ++ get(right))

    override def isCustomized(config: Config): Boolean =
      get(config).nonEmpty
  }

  def policyBinding[Config](
      key: SettingKey[Boolean],
      get: Config => Boolean,
      updated: (Config, Boolean) => Config
  ): PolicyBinding[Config] =
    PolicyBinding(key = key, get = get, updated = updated)

  def hookBinding[Config, Hook](
      key: SettingKey[Seq[Hook]],
      get: Config => Seq[Hook],
      updated: (Config, Seq[Hook]) => Config
  ): HookBinding[Config, Hook] =
    HookBinding(key = key, get = get, updated = updated)

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
      .flatMap(_.configBindings)
      .foldLeft((Vector.empty[Binding[Config]], Set.empty[String])) {
        case ((acc, seen), binding) if seen.contains(binding.id) => (acc, seen)
        case ((acc, seen), binding)                              => (acc :+ binding, seen + binding.id)
      }
      ._1
}
