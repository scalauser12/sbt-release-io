package io.release.runtime.engine

import cats.effect.IO
import cats.effect.Ref

private[release] object LifecycleCompiler {

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

  /** @param freezeGate when true, gate decisions are captured during
    *   validation and reused during execution so that state mutations
    *   between phases cannot flip the decision.
    * @param gateKey extracts a cache key from the context so that
    *   cross-build iterations (which change `scalaVersion` in state)
    *   each get their own frozen decision.  Required when
    *   `freezeGate` is true.
    */
  def singleHookPhase[Config, C, I, Hook](
      phase: String,
      resolveHooks: Config => Seq[Hook],
      gate: C => IO[Boolean],
      nameOf: Hook => String,
      executeOf: Hook => C => IO[C],
      validateOf: Hook => C => IO[Unit],
      crossBuild: Boolean = false,
      freezeGate: Boolean = false,
      gateKey: C => String = (_: C) => "",
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
            freezeGate = freezeGate,
            gateKey = gateKey
          )(
            nameOf = nameOf,
            executeOf = executeOf,
            validateOf = validateOf,
            crossBuild = crossBuild
          )
        else Seq.empty
    )

  /** @param freezeGate when true, gate decisions are captured during
    *   validation and reused during execution so that state mutations
    *   between phases cannot flip the decision.
    * @param gateKey extracts a stable cache key from context and item so
    *   that per-project and per-Scala-version iterations each preserve
    *   their own frozen decision.  Must use a stable project identifier
    *   (e.g. `ProjectRef`) rather than the full item, since item fields
    *   like `versions` and `tagName` change between phases.  Required
    *   when `freezeGate` is true.
    */
  def perItemHookPhase[Config, C, I, Hook](
      phase: String,
      resolveHooks: Config => Seq[Hook],
      gate: (C, I) => IO[Boolean],
      nameOf: Hook => String,
      executeOf: Hook => (C, I) => IO[C],
      validateOf: Hook => (C, I) => IO[Unit],
      crossBuild: Boolean = false,
      freezeGate: Boolean = false,
      gateKey: (C, I) => String = (_: C, _: I) => "",
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
            freezeGate = freezeGate,
            gateKey = gateKey
          )(
            nameOf = nameOf,
            executeOf = executeOf,
            validateOf = validateOf,
            crossBuild = crossBuild
          )
        else Seq.empty
    )

  def defaults[Config, C, I](
      phases: Seq[Phase[Config, C, I]]
  ): Seq[ProcessStep[C, I]] =
    phases.flatMap(_.rawSteps)

  def defaultsSingle[Config, C](
      phases: Seq[Phase[Config, C, Nothing]]
  ): Seq[ProcessStep.Single[C]] =
    defaults(phases).flatMap(step => ProcessStep.toSingleOption(step))

  def compile[Config, C, I](
      config: Config,
      phases: Seq[Phase[Config, C, I]]
  ): Seq[ProcessStep[C, I]] =
    phases.flatMap(_.compile(config))

  def compileSingle[Config, C](
      config: Config,
      phases: Seq[Phase[Config, C, Nothing]]
  ): Seq[ProcessStep.Single[C]] =
    compile(config, phases).flatMap(step => ProcessStep.toSingleOption(step))

  // ── Single-context hooks ────────────────────────────────────────────

  private def compileSingleContextHooks[C, Hook](
      phase: String,
      hooks: Seq[Hook],
      gate: C => IO[Boolean],
      freezeGate: Boolean,
      gateKey: C => String
  )(
      nameOf: Hook => String,
      executeOf: Hook => C => IO[C],
      validateOf: Hook => C => IO[Unit],
      crossBuild: Boolean
  ): Seq[ProcessStep.Single[C]] =
    hooks.map { hook =>
      val stepName = s"$phase:${nameOf(hook)}"

      if (freezeGate)
        frozenGateSingleStep(
          stepName,
          gate,
          gateKey,
          executeOf(hook),
          validateOf(hook),
          crossBuild
        )
      else
        ProcessStep.Single[C](
          name = stepName,
          execute = ctx =>
            gate(ctx).flatMap {
              case true  => executeOf(hook)(ctx)
              case false => IO.pure(ctx)
            },
          validate = ctx =>
            gate(ctx).flatMap {
              case true  => validateOf(hook)(ctx)
              case false => IO.unit
            },
          enableCrossBuild = crossBuild
        )
    }

  /** Build a single-context step whose gate decision is frozen at
    * validation time and reused during execution, keyed by
    * `gateKey(ctx)` so cross-build iterations each preserve their
    * own decision.
    */
  private def frozenGateSingleStep[C](
      name: String,
      gate: C => IO[Boolean],
      gateKey: C => String,
      execute: C => IO[C],
      validate: C => IO[Unit],
      crossBuild: Boolean
  ): ProcessStep.Single[C] = {
    val cached = Ref.unsafe[IO, Map[String, Boolean]](Map.empty)

    ProcessStep.Single[C](
      name = name,
      execute = ctx => {
        val key = gateKey(ctx)
        frozenGateRun(
          cached,
          key,
          gate(ctx),
          execute(ctx),
          IO.pure(ctx)
        )
      },
      enableCrossBuild = crossBuild,
      validateWithContext = Some(ctx => {
        val key = gateKey(ctx)
        frozenGateValidate(
          cached,
          key,
          gate(ctx),
          validate(ctx).as(ctx),
          IO.pure(ctx)
        )
      })
    )
  }

  // ── Per-item hooks ──────────────────────────────────────────────────

  private def compileItemHooks[C, I, Hook](
      phase: String,
      hooks: Seq[Hook],
      gate: (C, I) => IO[Boolean],
      freezeGate: Boolean,
      gateKey: (C, I) => String
  )(
      nameOf: Hook => String,
      executeOf: Hook => (C, I) => IO[C],
      validateOf: Hook => (C, I) => IO[Unit],
      crossBuild: Boolean
  ): Seq[ProcessStep.PerItem[C, I]] =
    hooks.map { hook =>
      val stepName = s"$phase:${nameOf(hook)}"

      if (freezeGate)
        frozenGateItemStep(
          stepName,
          gate,
          gateKey,
          executeOf(hook),
          validateOf(hook),
          crossBuild
        )
      else
        ProcessStep.PerItem[C, I](
          name = stepName,
          execute = (ctx, item) =>
            gate(ctx, item).flatMap {
              case true  => executeOf(hook)(ctx, item)
              case false => IO.pure(ctx)
            },
          validate = (ctx, item) =>
            gate(ctx, item).flatMap {
              case true  => validateOf(hook)(ctx, item)
              case false => IO.unit
            },
          enableCrossBuild = crossBuild
        )
    }

  /** Build a per-item step whose gate decision is frozen at validation
    * time and reused during execution, keyed by `gateKey(ctx, item)`
    * so each project+scalaVersion combination preserves its own
    * decision.
    */
  private def frozenGateItemStep[C, I](
      name: String,
      gate: (C, I) => IO[Boolean],
      gateKey: (C, I) => String,
      execute: (C, I) => IO[C],
      validate: (C, I) => IO[Unit],
      crossBuild: Boolean
  ): ProcessStep.PerItem[C, I] = {
    val cached = Ref.unsafe[IO, Map[String, Boolean]](Map.empty)

    ProcessStep.PerItem[C, I](
      name = name,
      execute = (ctx, item) => {
        val key = gateKey(ctx, item)
        frozenGateRun(
          cached,
          key,
          gate(ctx, item),
          execute(ctx, item),
          IO.pure(ctx)
        )
      },
      enableCrossBuild = crossBuild,
      validateWithContext = Some((ctx, item) => {
        val key = gateKey(ctx, item)
        frozenGateValidate(
          cached,
          key,
          gate(ctx, item),
          validate(ctx, item).as(ctx),
          IO.pure(ctx)
        )
      })
    )
  }

  private def frozenGateRun[C](
      cached: Ref[IO, Map[String, Boolean]],
      key: String,
      gateDecision: IO[Boolean],
      executeIfTrue: IO[C],
      skip: IO[C]
  ): IO[C] =
    cached.get.map(_.get(key)).flatMap {
      case Some(true)  => executeIfTrue
      case Some(false) => skip
      case None        =>
        gateDecision.flatMap {
          case true  => executeIfTrue
          case false => skip
        }
    }

  private def frozenGateValidate[C](
      cached: Ref[IO, Map[String, Boolean]],
      key: String,
      gateDecision: IO[Boolean],
      validateIfTrue: IO[C],
      skip: IO[C]
  ): IO[C] =
    gateDecision
      .flatTap(d => cached.update(_ + (key -> d)))
      .flatMap {
        case true  => validateIfTrue
        case false => skip
      }
}
