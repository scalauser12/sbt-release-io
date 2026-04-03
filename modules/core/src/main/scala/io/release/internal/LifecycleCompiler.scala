package io.release.internal

import cats.effect.IO

private[release] object LifecycleCompiler {

  sealed trait Phase[Config, C, I] {
    def rawSteps: Seq[ProcessStep[C, I]]
    def compile(config: Config): Seq[ProcessStep[C, I]]
  }

  final case class SingleBuiltInPhase[Config, C, I](
      step: ProcessStep.Single[C],
      enabled: Config => Boolean = (_: Config) => true
  ) extends Phase[Config, C, I] {
    override val rawSteps: Seq[ProcessStep[C, I]] = Seq(step)

    override def compile(config: Config): Seq[ProcessStep[C, I]] =
      if (enabled(config)) Seq(step) else Seq.empty
  }

  final case class PerItemBuiltInPhase[Config, C, I](
      step: ProcessStep.PerItem[C, I],
      enabled: Config => Boolean = (_: Config) => true
  ) extends Phase[Config, C, I] {
    override val rawSteps: Seq[ProcessStep[C, I]] = Seq(step)

    override def compile(config: Config): Seq[ProcessStep[C, I]] =
      if (enabled(config)) Seq(step) else Seq.empty
  }

  final case class SingleHookPhase[Config, C, I, Hook, Token](
      phase: String,
      resolveHooks: Config => Seq[Hook],
      gate: C => IO[Boolean],
      nameOf: Hook => String,
      executeOf: Hook => C => IO[C],
      validateOf: Hook => C => IO[Unit],
      crossBuild: Boolean = false,
      cachedGate: Option[HookStepCompilation.CachedSingleGate[C, Token]] = None,
      enabled: Config => Boolean = (_: Config) => true
  ) extends Phase[Config, C, I] {
    override val rawSteps: Seq[ProcessStep[C, I]] = Seq.empty

    override def compile(config: Config): Seq[ProcessStep[C, I]] =
      if (enabled(config))
        HookStepCompilation.compileSingleContextHooks(
          phase = phase,
          hooks = resolveHooks(config),
          gate = gate,
          cachedGate = cachedGate
        )(
          nameOf = nameOf,
          executeOf = executeOf,
          validateOf = validateOf,
          buildStep = (name, execute, validate, validateWithContext) =>
            ProcessStep.Single[C](
              name = name,
              execute = execute,
              validate = validate,
              enableCrossBuild = crossBuild,
              validateWithContext = validateWithContext
            )
        )
      else Seq.empty
  }

  final case class PerItemHookPhase[Config, C, I, Hook, Token](
      phase: String,
      resolveHooks: Config => Seq[Hook],
      gate: (C, I) => IO[Boolean],
      nameOf: Hook => String,
      executeOf: Hook => (C, I) => IO[C],
      validateOf: Hook => (C, I) => IO[Unit],
      crossBuild: Boolean = false,
      cachedGate: Option[HookStepCompilation.CachedItemGate[C, I, Token]] = None,
      enabled: Config => Boolean = (_: Config) => true
  ) extends Phase[Config, C, I] {
    override val rawSteps: Seq[ProcessStep[C, I]] = Seq.empty

    override def compile(config: Config): Seq[ProcessStep[C, I]] =
      if (enabled(config))
        HookStepCompilation.compileItemHooks(
          phase = phase,
          hooks = resolveHooks(config),
          gate = gate,
          cachedGate = cachedGate
        )(
          nameOf = nameOf,
          executeOf = executeOf,
          validateOf = validateOf,
          buildStep = (name, execute, validate, validateWithContext) =>
            ProcessStep.PerItem[C, I](
              name = name,
              execute = execute,
              validate = validate,
              enableCrossBuild = crossBuild,
              validateWithContext = validateWithContext
            )
        )
      else Seq.empty
  }

  def singleBuiltIn[Config, C, I](
      step: ProcessStep.Single[C],
      enabled: Config => Boolean = (_: Config) => true
  ): Phase[Config, C, I] =
    SingleBuiltInPhase(
      step = step,
      enabled = enabled
    )

  def perItemBuiltIn[Config, C, I](
      step: ProcessStep.PerItem[C, I],
      enabled: Config => Boolean = (_: Config) => true
  ): Phase[Config, C, I] =
    PerItemBuiltInPhase(
      step = step,
      enabled = enabled
    )

  def singleHookPhase[Config, C, I, Hook, Token](
      phase: String,
      resolveHooks: Config => Seq[Hook],
      gate: C => IO[Boolean],
      nameOf: Hook => String,
      executeOf: Hook => C => IO[C],
      validateOf: Hook => C => IO[Unit],
      crossBuild: Boolean = false,
      cachedGate: Option[HookStepCompilation.CachedSingleGate[C, Token]] = None,
      enabled: Config => Boolean = (_: Config) => true
  ): Phase[Config, C, I] =
    SingleHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = gate,
      nameOf = nameOf,
      executeOf = executeOf,
      validateOf = validateOf,
      crossBuild = crossBuild,
      cachedGate = cachedGate,
      enabled = enabled
    )

  def perItemHookPhase[Config, C, I, Hook, Token](
      phase: String,
      resolveHooks: Config => Seq[Hook],
      gate: (C, I) => IO[Boolean],
      nameOf: Hook => String,
      executeOf: Hook => (C, I) => IO[C],
      validateOf: Hook => (C, I) => IO[Unit],
      crossBuild: Boolean = false,
      cachedGate: Option[HookStepCompilation.CachedItemGate[C, I, Token]] = None,
      enabled: Config => Boolean = (_: Config) => true
  ): Phase[Config, C, I] =
    PerItemHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = gate,
      nameOf = nameOf,
      executeOf = executeOf,
      validateOf = validateOf,
      crossBuild = crossBuild,
      cachedGate = cachedGate,
      enabled = enabled
    )

  def defaults[Config, C, I](phases: Seq[Phase[Config, C, I]]): Seq[ProcessStep[C, I]] =
    phases.flatMap(_.rawSteps)

  def defaultsSingle[Config, C](
      phases: Seq[Phase[Config, C, Nothing]]
  ): Seq[ProcessStep.Single[C]] =
    defaults(phases).map(_.asInstanceOf[ProcessStep.Single[C]])

  def compile[Config, C, I](
      config: Config,
      phases: Seq[Phase[Config, C, I]]
  ): Seq[ProcessStep[C, I]] =
    phases.flatMap(_.compile(config))

  def compileSingle[Config, C](
      config: Config,
      phases: Seq[Phase[Config, C, Nothing]]
  ): Seq[ProcessStep.Single[C]] =
    compile(config, phases).map(_.asInstanceOf[ProcessStep.Single[C]])
}
