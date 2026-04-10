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
    defaults(phases).flatMap(step => narrowToSingle(step))

  def compile[Config, C, I](
      config: Config,
      phases: Seq[Phase[Config, C, I]]
  ): Seq[ProcessStep[C, I]] =
    phases.flatMap(_.compile(config))

  def compileSingle[Config, C](
      config: Config,
      phases: Seq[Phase[Config, C, Nothing]]
  ): Seq[ProcessStep.Single[C]] =
    compile(config, phases).flatMap(step => narrowToSingle(step))

  /** Narrows steps typed with phantom `Nothing` to [[ProcessStep.Single]].
    *
    * `Phase[..., Nothing]` is used for pipelines that only contain global (single-context)
    * steps; [[ProcessStep.PerItem]] with `I = Nothing` is not produced by this compiler and
    * is dropped if present.
    */
  private def narrowToSingle[C](
      step: ProcessStep[C, Nothing]
  ): Option[ProcessStep.Single[C]] =
    ProcessStep.fold[C, Nothing, Option[ProcessStep.Single[C]]](step)(
      Some(_),
      _ => None
    )

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
        cached.get.map(_.get(key)).flatMap {
          case Some(true)  => execute(ctx)
          case Some(false) => IO.pure(ctx)
          case None        =>
            gate(ctx).flatMap {
              case true  => execute(ctx)
              case false => IO.pure(ctx)
            }
        }
      },
      enableCrossBuild = crossBuild,
      validateWithContext = Some(ctx => {
        val key = gateKey(ctx)
        gate(ctx)
          .flatTap(d => cached.update(_ + (key -> d)))
          .flatMap {
            case true  => validate(ctx).as(ctx)
            case false => IO.pure(ctx)
          }
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
        cached.get.map(_.get(key)).flatMap {
          case Some(true)  => execute(ctx, item)
          case Some(false) => IO.pure(ctx)
          case None        =>
            gate(ctx, item).flatMap {
              case true  => execute(ctx, item)
              case false => IO.pure(ctx)
            }
        }
      },
      enableCrossBuild = crossBuild,
      validateWithContext = Some((ctx, item) => {
        val key = gateKey(ctx, item)
        gate(ctx, item)
          .flatTap(d => cached.update(_ + (key -> d)))
          .flatMap {
            case true  => validate(ctx, item).as(ctx)
            case false => IO.pure(ctx)
          }
      })
    )
  }
}
