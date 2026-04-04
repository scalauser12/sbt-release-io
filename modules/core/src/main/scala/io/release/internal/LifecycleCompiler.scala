package io.release.internal

import cats.effect.IO

private[release] object LifecycleCompiler {

  final case class Phase[Config, C, I](
      phaseName: Option[String],
      configBindings: Seq[LifecycleConfigCompiler.Binding[Config]],
      rawSteps: Seq[ProcessStep[C, I]],
      compileSteps: Config => Seq[ProcessStep[C, I]]
  ) {
    def compile(config: Config): Seq[ProcessStep[C, I]] =
      compileSteps(config)
  }

  def singleBuiltIn[Config, C, I](
      step: ProcessStep.Single[C],
      enabled: Config => Boolean = (_: Config) => true,
      configBindings: Seq[LifecycleConfigCompiler.Binding[Config]] = Nil
  ): Phase[Config, C, I] =
    Phase(
      phaseName = None,
      configBindings = configBindings,
      rawSteps = Seq(step),
      compileSteps = config => if (enabled(config)) Seq(step) else Seq.empty
    )

  def perItemBuiltIn[Config, C, I](
      step: ProcessStep.PerItem[C, I],
      enabled: Config => Boolean = (_: Config) => true,
      configBindings: Seq[LifecycleConfigCompiler.Binding[Config]] = Nil
  ): Phase[Config, C, I] =
    Phase(
      phaseName = None,
      configBindings = configBindings,
      rawSteps = Seq(step),
      compileSteps = config => if (enabled(config)) Seq(step) else Seq.empty
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
      enabled: Config => Boolean = (_: Config) => true,
      configBindings: Seq[LifecycleConfigCompiler.Binding[Config]] = Nil
  ): Phase[Config, C, I] =
    Phase(
      phaseName = Some(phase),
      configBindings = configBindings,
      rawSteps = Seq.empty,
      compileSteps = config =>
        if (enabled(config))
          HookStepCompilation.compileSingleContextHooks(
            phase = phase,
            hooks = resolveHooks(config),
            gate = gate,
            cachedGate = cachedGate
          )(
            nameOf = (hook: Hook) => nameOf(hook),
            executeOf = (hook: Hook) => executeOf(hook),
            validateOf = (hook: Hook) => validateOf(hook),
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
      enabled: Config => Boolean = (_: Config) => true,
      configBindings: Seq[LifecycleConfigCompiler.Binding[Config]] = Nil
  ): Phase[Config, C, I] =
    Phase(
      phaseName = Some(phase),
      configBindings = configBindings,
      rawSteps = Seq.empty,
      compileSteps = config =>
        if (enabled(config))
          HookStepCompilation.compileItemHooks(
            phase = phase,
            hooks = resolveHooks(config),
            gate = gate,
            cachedGate = cachedGate
          )(
            nameOf = (hook: Hook) => nameOf(hook),
            executeOf = (hook: Hook) => executeOf(hook),
            validateOf = (hook: Hook) => validateOf(hook),
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
