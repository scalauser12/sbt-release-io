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

      if (freezeGate) {
        val (frozenExec, frozenVal) =
          frozenGateFunctions[C, C](
            gate,
            gateKey,
            execute = executeOf(hook),
            validate = ctx => validateOf(hook)(ctx).as(ctx),
            skip = ctx => IO.pure(ctx)
          )
        ProcessStep.Single[C](
          name = stepName,
          execute = frozenExec,
          enableCrossBuild = crossBuild,
          validateWithContext = Some(frozenVal)
        )
      } else
        ProcessStep.Single[C](
          name = stepName,
          execute = ctx =>
            gatedExecute(
              gate(ctx),
              executeOf(hook)(ctx),
              IO.pure(ctx)
            ),
          validate = ctx => gatedValidate(gate(ctx), validateOf(hook)(ctx)),
          enableCrossBuild = crossBuild
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

      if (freezeGate) {
        val (frozenExec, frozenVal) =
          frozenGateFunctions[(C, I), C](
            gate = { case (c, i) => gate(c, i) },
            gateKey = { case (c, i) => gateKey(c, i) },
            execute = { case (c, i) => executeOf(hook)(c, i) },
            validate = { case (c, i) =>
              validateOf(hook)(c, i).as(c)
            },
            skip = { case (c, _) => IO.pure(c) }
          )
        ProcessStep.PerItem[C, I](
          name = stepName,
          execute = (ctx, item) => frozenExec((ctx, item)),
          enableCrossBuild = crossBuild,
          validateWithContext = Some((ctx, item) => frozenVal((ctx, item)))
        )
      } else
        ProcessStep.PerItem[C, I](
          name = stepName,
          execute = (ctx, item) =>
            gatedExecute(
              gate(ctx, item),
              executeOf(hook)(ctx, item),
              IO.pure(ctx)
            ),
          validate = (ctx, item) =>
            gatedValidate(
              gate(ctx, item),
              validateOf(hook)(ctx, item)
            ),
          enableCrossBuild = crossBuild
        )
    }

  // ── Shared gate helpers ─────────────────────────────────────────────

  private def gatedExecute[C](
      gate: IO[Boolean],
      run: IO[C],
      skip: IO[C]
  ): IO[C] =
    gate.flatMap {
      case true  => run
      case false => skip
    }

  private def gatedValidate(
      gate: IO[Boolean],
      run: IO[Unit]
  ): IO[Unit] =
    gate.flatMap {
      case true  => run
      case false => IO.unit
    }

  /** Build frozen-gate execute and validate functions that share a
    * single `Ref` cache.  The validate function captures the gate
    * decision; the execute function replays it.  Keyed by `gateKey`
    * so cross-build or per-project iterations each preserve their
    * own decision.
    */
  private def frozenGateFunctions[Args, C](
      gate: Args => IO[Boolean],
      gateKey: Args => String,
      execute: Args => IO[C],
      validate: Args => IO[C],
      skip: Args => IO[C]
  ): (Args => IO[C], Args => IO[C]) = {
    val cached               =
      Ref.unsafe[IO, Map[String, Boolean]](Map.empty)
    val exec: Args => IO[C]  = args =>
      frozenGateRun(
        cached,
        gateKey(args),
        gate(args),
        execute(args),
        skip(args)
      )
    val valFn: Args => IO[C] = args =>
      frozenGateValidate(
        cached,
        gateKey(args),
        gate(args),
        validate(args),
        skip(args)
      )
    (exec, valFn)
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
