package io.release.runtime.engine

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.traverse.*
import io.release.runtime.TrackedContextHandle

private[release] object LifecycleCompiler {

  final case class Phase[Config, C, I](
      phaseName: Option[String],
      rawSteps: Seq[ProcessStep[C, I]],
      compileSteps: Config => IO[Seq[ProcessStep[C, I]]]
  ) {
    def compile(config: Config): IO[Seq[ProcessStep[C, I]]] =
      compileSteps(config)
  }

  def builtIn[Config, C, I](
      step: ProcessStep[C, I],
      enabled: Config => Boolean = (_: Config) => true
  ): Phase[Config, C, I] =
    Phase(
      phaseName = None,
      rawSteps = Seq(step),
      compileSteps = config => IO.pure(if (enabled(config)) Seq(step) else Seq.empty)
    )

  /** Compile a single-context hook phase. See the *freeze-gate contract* note below for
    * `freezeGateKey` semantics.
    *
    * @param freezeGateKey when `Some(fn)`, the gate is frozen — captured at validate time
    *   and replayed at execute time, with `fn(ctx)` naming the cache slot per iteration
    *   (cross-build, per-project). When `None`, the gate is streaming (re-evaluated each
    *   call). For cross-build iterations, fold `scalaVersion` into the key so each
    *   iteration gets its own frozen decision.
    * @param narrowExecute optional execute-time predicate AND'd with `gate` (or, when
    *   `freezeGateKey` is set, with the cached validate-time gate decision). Lets a phase
    *   use the validate-time gate as an upper bound while further gating execution on a
    *   runtime signal the validate phase cannot observe (e.g. "did the publish task
    *   actually run?", "did the push actually go through?"). Validation is unaffected,
    *   preserving the validate-before-execute contract.
    */
  def singleHookPhase[Config, C, I, Hook](
      phase: String,
      resolveHooks: Config => Seq[Hook],
      gate: C => IO[Boolean],
      nameOf: Hook => String,
      executeOf: Hook => C => IO[C],
      executeTrackedOf: Option[Hook => TrackedContextHandle[C] => IO[Unit]] = None,
      validateOf: Hook => C => IO[Unit],
      crossBuild: Boolean = false,
      freezeGateKey: Option[C => String] = None,
      enabled: Config => Boolean = (_: Config) => true,
      narrowExecute: Option[C => IO[Boolean]] = None
  ): Phase[Config, C, I] = {
    val trackedExecuteOf =
      executeTrackedOf.getOrElse((hook: Hook) => TrackedContextHandle.lift(executeOf(hook)))
    Phase(
      phaseName = Some(phase),
      rawSteps = Seq.empty,
      compileSteps = config =>
        if (enabled(config))
          compileSingleContextHooks(
            phase = phase,
            hooks = resolveHooks(config),
            gate = gate,
            gateMode = freezeGateKey,
            narrowExecute = narrowExecute
          )(
            nameOf = nameOf,
            executeOf = executeOf,
            executeTrackedOf = trackedExecuteOf,
            validateOf = validateOf,
            crossBuild = crossBuild
          )
        else IO.pure(Seq.empty)
    )
  }

  /** Compile a per-item hook phase. See the *freeze-gate contract* note below for
    * `freezeGateKey` semantics.
    *
    * @param freezeGateKey when `Some(fn)`, the gate is frozen — captured at validate time
    *   and replayed at execute time, with `fn(ctx, item)` naming the cache slot per
    *   iteration. Must use a stable project identifier (e.g. `ProjectRef`) rather than
    *   the full item, since item fields like `versions` and `tagName` change between
    *   phases. When `None`, the gate is streaming (re-evaluated each call).
    * @param narrowExecute optional execute-time predicate AND'd with `gate` (or, when
    *   `freezeGateKey` is set, with the cached validate-time gate decision). Lets a phase
    *   use the validate-time gate as an upper bound while further gating execution on a
    *   runtime signal the validate phase cannot observe (e.g. "did the publish task
    *   actually run?", "did the push actually go through?"). Validation is unaffected,
    *   preserving the validate-before-execute contract.
    */
  def perItemHookPhase[Config, C, I, Hook](
      phase: String,
      resolveHooks: Config => Seq[Hook],
      gate: (C, I) => IO[Boolean],
      nameOf: Hook => String,
      executeOf: Hook => (C, I) => IO[C],
      executeTrackedOf: Option[Hook => (TrackedContextHandle[C], I) => IO[Unit]] = None,
      validateOf: Hook => (C, I) => IO[Unit],
      crossBuild: Boolean = false,
      freezeGateKey: Option[(C, I) => String] = None,
      enabled: Config => Boolean = (_: Config) => true,
      narrowExecute: Option[(C, I) => IO[Boolean]] = None
  ): Phase[Config, C, I] = {
    val trackedExecuteOf =
      executeTrackedOf.getOrElse((hook: Hook) => TrackedContextHandle.liftPerItem(executeOf(hook)))
    Phase(
      phaseName = Some(phase),
      rawSteps = Seq.empty,
      compileSteps = config =>
        if (enabled(config))
          compileItemHooks(
            phase = phase,
            hooks = resolveHooks(config),
            gate = gate,
            gateMode = freezeGateKey,
            narrowExecute = narrowExecute
          )(
            nameOf = nameOf,
            executeOf = executeOf,
            executeTrackedOf = trackedExecuteOf,
            validateOf = validateOf,
            crossBuild = crossBuild
          )
        else IO.pure(Seq.empty)
    )
  }

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
  ): IO[Seq[ProcessStep[C, I]]] =
    phases.toList.traverse(_.compile(config)).map(_.flatten)

  def compileSingle[Config, C](
      config: Config,
      phases: Seq[Phase[Config, C, Nothing]]
  ): IO[Seq[ProcessStep.Single[C]]] =
    compile(config, phases).map(_.flatMap(step => ProcessStep.toSingleOption(step)))

  // ── Freeze-gate contract ────────────────────────────────────────────
  //
  // When `freezeGateKey = Some(fn)`, gate decisions are captured during
  // validation and replayed during execution so state mutations between
  // phases cannot flip the decision. `fn` names the cache slot each
  // iteration (cross-build, per-project, …) reads from and writes to.
  // When `freezeGateKey = None`, the gate is streaming — every call
  // re-evaluates the current `gate` function.

  // ── Single-context hooks ────────────────────────────────────────────

  private def compileSingleContextHooks[C, Hook](
      phase: String,
      hooks: Seq[Hook],
      gate: C => IO[Boolean],
      gateMode: Option[C => String],
      narrowExecute: Option[C => IO[Boolean]]
  )(
      nameOf: Hook => String,
      executeOf: Hook => C => IO[C],
      executeTrackedOf: Hook => TrackedContextHandle[C] => IO[Unit],
      validateOf: Hook => C => IO[Unit],
      crossBuild: Boolean
  ): IO[Seq[ProcessStep.Single[C]]] = {
    val applyNarrow: (C => IO[C]) => C => IO[C]                                        =
      narrowExecute match {
        case Some(narrow) =>
          f => c => narrow(c).ifM(f(c), IO.pure(c))
        case None         =>
          identity
      }
    val applyNarrowTracked
        : (TrackedContextHandle[C] => IO[Unit]) => TrackedContextHandle[C] => IO[Unit] =
      narrowExecute match {
        case Some(narrow) =>
          f => handle => handle.get.flatMap(ctx => narrow(ctx).ifM(f(handle), IO.unit))
        case None         =>
          identity
      }

    hooks.toList.traverse { hook =>
      val stepName = s"$phase:${nameOf(hook)}"

      gateMode match {
        case Some(stableGateKey) =>
          val narrowedExecute        = applyNarrow(executeOf(hook))
          val narrowedExecuteTracked = applyNarrowTracked(executeTrackedOf(hook))

          frozenGateFunctions[C, C](
            gate,
            stableGateKey,
            execute = narrowedExecute,
            executeTracked = (handle, _) => narrowedExecuteTracked(handle),
            validate = ctx => validateOf(hook)(ctx).as(ctx),
            skip = ctx => IO.pure(ctx)
          ).map { case (frozenExec, frozenExecTracked, frozenVal) =>
            ProcessStep.Single[C](
              stepName,
              frozenExec,
              Some((handle: TrackedContextHandle[C]) =>
                handle.get.flatMap((ctx: C) => frozenExecTracked(handle, ctx))
              ),
              (_: C) => IO.unit,
              Set.empty[BuiltInStepRole],
              crossBuild,
              Some(frozenVal): Option[C => IO[C]]
            )
          }
        case None                =>
          // `narrowExecute` is execute-time only — validation rehearses the hook on
          // `gate` alone so `releaseIO check` exercises hooks the runtime gate would
          // later suppress (e.g. afterPush when no push happened).
          val narrowedExecute        = applyNarrow(executeOf(hook))
          val narrowedExecuteTracked = applyNarrowTracked(executeTrackedOf(hook))
          IO.pure(
            ProcessStep.Single[C](
              stepName,
              (ctx: C) => gate(ctx).ifM(narrowedExecute(ctx), IO.pure(ctx)),
              Some((handle: TrackedContextHandle[C]) =>
                handle.get.flatMap((ctx: C) =>
                  gate(ctx).ifM(narrowedExecuteTracked(handle), IO.unit)
                )
              ),
              (ctx: C) => gate(ctx).ifM(validateOf(hook)(ctx), IO.unit),
              Set.empty[BuiltInStepRole],
              crossBuild,
              None: Option[C => IO[C]]
            )
          )
      }
    }
  }

  // ── Per-item hooks ──────────────────────────────────────────────────

  private def compileItemHooks[C, I, Hook](
      phase: String,
      hooks: Seq[Hook],
      gate: (C, I) => IO[Boolean],
      gateMode: Option[(C, I) => String],
      narrowExecute: Option[(C, I) => IO[Boolean]]
  )(
      nameOf: Hook => String,
      executeOf: Hook => (C, I) => IO[C],
      executeTrackedOf: Hook => (TrackedContextHandle[C], I) => IO[Unit],
      validateOf: Hook => (C, I) => IO[Unit],
      crossBuild: Boolean
  ): IO[Seq[ProcessStep.PerItem[C, I]]] = {
    val applyNarrow: ((C, I) => IO[C]) => (C, I) => IO[C]                                        =
      narrowExecute match {
        case Some(narrow) =>
          f => (c, i) => narrow(c, i).ifM(f(c, i), IO.pure(c))
        case None         =>
          identity
      }
    val applyNarrowTracked
        : ((TrackedContextHandle[C], I) => IO[Unit]) => (TrackedContextHandle[C], I) => IO[Unit] =
      narrowExecute match {
        case Some(narrow) =>
          f =>
            (handle, item) =>
              handle.get.flatMap(ctx => narrow(ctx, item).ifM(f(handle, item), IO.unit))
        case None         =>
          identity
      }

    hooks.toList.traverse { hook =>
      val stepName = s"$phase:${nameOf(hook)}"

      gateMode match {
        case Some(stableGateKey) =>
          val narrowedExecute        = applyNarrow(executeOf(hook))
          val narrowedExecuteTracked = applyNarrowTracked(executeTrackedOf(hook))

          frozenGateFunctions[(C, I), C](
            gate = { case (c, i) => gate(c, i) },
            gateKey = { case (c, i) => stableGateKey(c, i) },
            execute = { case (c, i) => narrowedExecute(c, i) },
            executeTracked = (handle, args) => narrowedExecuteTracked(handle, args._2),
            validate = { case (c, i) =>
              validateOf(hook)(c, i).as(c)
            },
            skip = { case (c, _) => IO.pure(c) }
          ).map { case (frozenExec, frozenExecTracked, frozenVal) =>
            ProcessStep.PerItem[C, I](
              stepName,
              (ctx: C, item: I) => frozenExec((ctx, item)),
              Some((handle: TrackedContextHandle[C], item: I) =>
                handle.get.flatMap((ctx: C) => frozenExecTracked(handle, (ctx, item)))
              ),
              (_: C, _: I) => IO.unit,
              Set.empty[BuiltInStepRole],
              crossBuild,
              Some((ctx: C, item: I) => frozenVal((ctx, item))): Option[(C, I) => IO[C]]
            )
          }
        case None                =>
          // `narrowExecute` is execute-time only; validation rehearses the hook on
          // `gate` alone so per-item check passes still exercise the hook code.
          val narrowedExecute        = applyNarrow(executeOf(hook))
          val narrowedExecuteTracked = applyNarrowTracked(executeTrackedOf(hook))
          IO.pure(
            ProcessStep.PerItem[C, I](
              stepName,
              (ctx: C, item: I) =>
                gate(ctx, item).ifM(
                  narrowedExecute(ctx, item),
                  IO.pure(ctx)
                ),
              Some((handle: TrackedContextHandle[C], item: I) =>
                handle.get.flatMap((ctx: C) =>
                  gate(ctx, item).ifM(narrowedExecuteTracked(handle, item), IO.unit)
                )
              ),
              (ctx: C, item: I) =>
                gate(ctx, item).ifM(
                  validateOf(hook)(ctx, item),
                  IO.unit
                ),
              Set.empty[BuiltInStepRole],
              crossBuild,
              None: Option[(C, I) => IO[C]]
            )
          )
      }
    }
  }

  // ── Shared gate helpers ─────────────────────────────────────────────

  /** Build frozen-gate execute and validate functions that share a
    * single `Ref` cache.  The validate function captures the gate
    * decision; the execute function replays it.  Keyed by `gateKey`
    * so cross-build or per-project iterations each preserve their
    * own decision.  Compiled release flows validate each hook step
    * once before its matching execute, so a simple cache update is
    * sufficient here.
    */
  private def frozenGateFunctions[Args, C](
      gate: Args => IO[Boolean],
      gateKey: Args => String,
      execute: Args => IO[C],
      executeTracked: (TrackedContextHandle[C], Args) => IO[Unit],
      validate: Args => IO[C],
      skip: Args => IO[C]
  ): IO[(Args => IO[C], (TrackedContextHandle[C], Args) => IO[Unit], Args => IO[C])] =
    Ref.of[IO, Map[String, Boolean]](Map.empty).map { cached =>
      val exec: Args => IO[C]  = args =>
        frozenGateRun(
          cached,
          gateKey(args),
          execute(args),
          skip(args)
        )
      val execTracked          = (handle: TrackedContextHandle[C], args: Args) =>
        frozenGateRunTracked(
          cached,
          gateKey(args),
          executeTracked(handle, args)
        )
      val valFn: Args => IO[C] = args =>
        frozenGateValidate(
          cached,
          gateKey(args),
          gate(args),
          validate(args),
          skip(args)
        )
      (exec, execTracked, valFn)
    }

  private def requireFrozenDecision(
      cached: Ref[IO, Map[String, Boolean]],
      key: String
  ): IO[Boolean] =
    cached.get.map(_.get(key)).flatMap {
      case Some(decision) => IO.pure(decision)
      case None           =>
        IO.raiseError(
          new IllegalStateException(
            s"Frozen gate decision missing for key '$key'; validate must run before execute when freezeGateKey is set"
          )
        )
    }

  private def frozenGateRun[C](
      cached: Ref[IO, Map[String, Boolean]],
      key: String,
      executeIfTrue: IO[C],
      skip: IO[C]
  ): IO[C] =
    requireFrozenDecision(cached, key).flatMap(if (_) executeIfTrue else skip)

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

  private def frozenGateRunTracked(
      cached: Ref[IO, Map[String, Boolean]],
      key: String,
      executeIfTrue: IO[Unit]
  ): IO[Unit] =
    requireFrozenDecision(cached, key).flatMap(if (_) executeIfTrue else IO.unit)
}
