package io.release.internal

import cats.effect.IO

private[release] object LifecycleCompiler {

  final case class CachedSingleGate[C, Token](
      tokenForIndex: Int => Token,
      resolveDecision: (C, Token, IO[Boolean]) => IO[Boolean],
      snapshotDecision: (C, Token, C => IO[Boolean]) => IO[(C, Boolean)]
  )

  final case class CachedItemGate[C, I, Token](
      tokenForIndex: Int => Token,
      resolveDecision: (C, Token, I, IO[Boolean]) => IO[Boolean],
      snapshotDecision: (C, Token, I, (C, I) => IO[Boolean]) => IO[(C, Boolean)]
  )

  final case class Phase[Config, C, I](
      phaseName: Option[String],
      rawSteps: Seq[ProcessStep[C, I]],
      compileSteps: Config => Seq[ProcessStep[C, I]]
  ) {
    def compile(config: Config): Seq[ProcessStep[C, I]] =
      compileSteps(config)
  }

  def singleBuiltIn[Config, C, I](
      step: ProcessStep.Single[C],
      enabled: Config => Boolean = (_: Config) => true
  ): Phase[Config, C, I] =
    Phase(
      phaseName = None,
      rawSteps = Seq(step),
      compileSteps = config => if (enabled(config)) Seq(step) else Seq.empty
    )

  def perItemBuiltIn[Config, C, I](
      step: ProcessStep.PerItem[C, I],
      enabled: Config => Boolean = (_: Config) => true
  ): Phase[Config, C, I] =
    Phase(
      phaseName = None,
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
      cachedGate: Option[CachedSingleGate[C, Token]] = None,
      enabled: Config => Boolean = (_: Config) => true
  ): Phase[Config, C, I] =
    Phase(
      phaseName = Some(phase),
      rawSteps = Seq.empty,
      compileSteps = config =>
        if (enabled(config))
          compileSingleContextHooks(
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
      cachedGate: Option[CachedItemGate[C, I, Token]] = None,
      enabled: Config => Boolean = (_: Config) => true
  ): Phase[Config, C, I] =
    Phase(
      phaseName = Some(phase),
      rawSteps = Seq.empty,
      compileSteps = config =>
        if (enabled(config))
          compileItemHooks(
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
    defaults(phases).collect { case s: ProcessStep.Single[C @unchecked] => s }

  def compile[Config, C, I](
      config: Config,
      phases: Seq[Phase[Config, C, I]]
  ): Seq[ProcessStep[C, I]] =
    phases.flatMap(_.compile(config))

  def compileSingle[Config, C](
      config: Config,
      phases: Seq[Phase[Config, C, Nothing]]
  ): Seq[ProcessStep.Single[C]] =
    compile(config, phases).collect { case s: ProcessStep.Single[C @unchecked] => s }

  private def compileSingleContextHooks[C, Hook, Step, Token](
      phase: String,
      hooks: Seq[Hook],
      gate: C => IO[Boolean],
      cachedGate: Option[CachedSingleGate[C, Token]] = None
  )(
      nameOf: Hook => String,
      executeOf: Hook => C => IO[C],
      validateOf: Hook => C => IO[Unit],
      buildStep: (String, C => IO[C], C => IO[Unit], Option[C => IO[C]]) => Step
  ): Seq[Step] =
    hooks.zipWithIndex.map { case (hook, hookIndex) =>
      val stepName = s"$phase:${nameOf(hook)}"

      cachedGate match {
        case Some(cache) =>
          val token = cache.tokenForIndex(hookIndex)
          buildStep(
            stepName,
            ctx =>
              cache.resolveDecision(ctx, token, gate(ctx)).flatMap {
                case true  => executeOf(hook)(ctx)
                case false => IO.pure(ctx)
              },
            _ => IO.unit,
            Some(ctx =>
              cache.snapshotDecision(ctx, token, gate).flatMap {
                case (updatedCtx, true)  => validateOf(hook)(updatedCtx).as(updatedCtx)
                case (updatedCtx, false) => IO.pure(updatedCtx)
              }
            )
          )

        case None =>
          buildStep(
            stepName,
            ctx =>
              gate(ctx).flatMap {
                case true  => executeOf(hook)(ctx)
                case false => IO.pure(ctx)
              },
            ctx =>
              gate(ctx).flatMap {
                case true  => validateOf(hook)(ctx)
                case false => IO.unit
              },
            None
          )
      }
    }

  private def compileItemHooks[C, I, Hook, Step, Token](
      phase: String,
      hooks: Seq[Hook],
      gate: (C, I) => IO[Boolean],
      cachedGate: Option[CachedItemGate[C, I, Token]] = None
  )(
      nameOf: Hook => String,
      executeOf: Hook => (C, I) => IO[C],
      validateOf: Hook => (C, I) => IO[Unit],
      buildStep: (
          String,
          (C, I) => IO[C],
          (C, I) => IO[Unit],
          Option[(C, I) => IO[C]]
      ) => Step
  ): Seq[Step] =
    hooks.zipWithIndex.map { case (hook, hookIndex) =>
      val stepName = s"$phase:${nameOf(hook)}"

      cachedGate match {
        case Some(cache) =>
          val token = cache.tokenForIndex(hookIndex)
          buildStep(
            stepName,
            (ctx, item) =>
              cache.resolveDecision(ctx, token, item, gate(ctx, item)).flatMap {
                case true  => executeOf(hook)(ctx, item)
                case false => IO.pure(ctx)
              },
            (_, _) => IO.unit,
            Some((ctx, item) =>
              cache.snapshotDecision(ctx, token, item, gate).flatMap {
                case (updatedCtx, true)  => validateOf(hook)(updatedCtx, item).as(updatedCtx)
                case (updatedCtx, false) => IO.pure(updatedCtx)
              }
            )
          )

        case None =>
          buildStep(
            stepName,
            (ctx, item) =>
              gate(ctx, item).flatMap {
                case true  => executeOf(hook)(ctx, item)
                case false => IO.pure(ctx)
              },
            (ctx, item) =>
              gate(ctx, item).flatMap {
                case true  => validateOf(hook)(ctx, item)
                case false => IO.unit
              },
            None
          )
      }
    }
}
