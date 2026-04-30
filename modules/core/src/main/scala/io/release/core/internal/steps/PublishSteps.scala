package io.release.core.internal.steps

import cats.effect.IO
import io.release.CleanCompat
import io.release.ReleaseContext
import io.release.ReleaseIOCompat
import io.release.ReleasePluginIO.autoImport.releaseIOPublishChecks
import io.release.ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies
import io.release.ReleaseSharedKeys.releaseIOPublishAction
import io.release.core.internal.CoreStepAliases.Step
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.sbt.SnapshotDependencyTasks
import io.release.runtime.workflow.DecisionResolver
import io.release.runtime.workflow.PublishValidation
import io.release.runtime.workflow.StepHelpers.*
import sbt.Keys.*
import sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Publish, test, and dependency-related release steps. */
private[release] object PublishSteps {

  import CoreReleaseStepHelpers.failOnSbtTaskFailure

  val checkSnapshotDependencies: Step = ProcessStep.Single(
    name = "check-snapshot-dependencies",
    execute = ctx => IO.pure(ctx),
    validateWithContext = Some(ctx =>
      SnapshotDependencyTasks
        .aggregatedSnapshotDependencies(ctx.state, releaseIODiagnosticsSnapshotDependencies)
        .flatMap {
          case Left(err)                    =>
            IO.raiseError[ReleaseContext](new IllegalStateException(err))
          case Right(deps) if deps.nonEmpty =>
            DecisionResolver.handleSnapshotDependencies(
              ctx,
              deps,
              ReleaseLogPrefixes.Core
            )
          case Right(_)                     => IO.pure(ctx)
        }
    ),
    enableCrossBuild = true
  )

  /** Per-iteration cache key for publish-related decisions. Cross-build core releases
    * iterate by `scalaVersion`, and each iteration must be tracked independently so
    * `after-publish` can observe whether `publish-artifacts` actually ran for *this*
    * iteration rather than reusing a frozen pre-publish prediction.
    */
  private[release] val publishGateKey: ReleaseContext => String =
    ctx =>
      SbtRuntime
        .extracted(ctx.state)
        .getOpt(scalaVersion)
        .getOrElse("")

  val publishArtifacts: Step = ProcessStep.Single(
    name = "publish-artifacts",
    roles = Set(BuiltInStepRole.PublishArtifacts),
    execute = ctx => {
      // Marking the publish-execution snapshot here ensures `after-publish` hooks
      // observe an initialized `publishExecutedKeys` set even when the publish
      // step skipped, so the gate distinguishes "publish step ran" from
      // "publish step never ran".
      val startedCtx = ctx.markPublishExecutionStarted
      // Replay the validate-time skip *only when it was true*. This locks down
      // the case where validation skipped the publishTo / `publish / skip`
      // checks under `ctx.skipPublish = true` — a hook that subsequently flips
      // it to false at execute time must not bypass those checks. The reverse
      // direction (validate-time false → execute-time true) stays observed
      // because hooks legitimately suppress publish via that path
      // (see ReleaseHookIO.transform docstring example). When validate has
      // not run (unit-test paths), the frozen entry is absent and we fall
      // back to the live `skipPublish` value.
      val skip       = startedCtx.publishSkipFrozen.contains(true) || startedCtx.skipPublish
      if (skip) {
        IO.blocking(startedCtx.state.log.info(s"${ReleaseLogPrefixes.Core} Skipping publish"))
          .as(startedCtx)
      } else {
        // Re-evaluate `publish / skip` and project membership at execute time directly
        // against the post-hook state so a `before-publish` hook that installed
        // `publish / skip := true` via session settings is observed here. Without this,
        // runAggregated would honor the skip and no-op while the after-publish gate
        // would still fire on a frozen pre-publish prediction. The propagating variant
        // uses `runTaskChecked`, which raises if a task-valued `publish / skip` arms
        // sbt's FailureCommand sentinel — preserving that signal even when the task
        // simultaneously returns `true` and we would otherwise short-circuit the
        // publish task that normally surfaces the failure.
        anyTargetWillPublishPropagating(
          startedCtx.state,
          s"publish-artifacts: sbt task '${(publish / Keys.skip).key.label}'"
        ).flatMap { case (postSkipState, actuallyRuns) =>
          val postSkipCtx = startedCtx.withState(postSkipState)
          if (!actuallyRuns)
            IO.blocking(
              postSkipCtx.state.log
                .info(s"${ReleaseLogPrefixes.Core} Skipping publish (publish / skip := true)")
            ).as(postSkipCtx)
          else
            // Persistent overlays installed by set-release-version, commit-release-version,
            // and tag-release live in `session.rawAppend` (via SbtRuntime.appendSessionSettings),
            // so the publish task observes the post-release-version state without any local
            // re-application here.
            IO.blocking {
              val extracted = SbtRuntime.extracted(postSkipCtx.state)
              val newState  = extracted.runAggregated(
                extracted.currentRef / releaseIOPublishAction,
                postSkipCtx.state
              )
              failOnSbtTaskFailure(
                postSkipCtx,
                newState,
                s"publish-artifacts: sbt task '${releaseIOPublishAction.key.label}' " +
                  "reported failure via FailureCommand"
              )
            }.map(_.recordPublishExecuted(publishGateKey(postSkipCtx)))
        }
      }
    },
    validateWithContext = Some(ctx => {
      // Capture the validate-time `skipPublish` decision into context metadata so
      // execute replays the same decision instead of re-reading the live field.
      // This closes the asymmetry where a hook running after validation but
      // before publish could flip `skipPublish` from `true` to `false` and
      // bypass the publishTo / `publish / skip` checks that were skipped here.
      // `freezePublishSkip` is idempotent across cross-build iterations: only
      // the first call sets the metadata, so subsequent iterations preserve
      // the original decision rather than overwriting it.
      val frozenCtx        = ctx.freezePublishSkip(ctx.skipPublish)
      // `skip` here is the validate-time decision (which is what we just
      // captured). `frozenCtx.skipPublish` is the same value at validate time;
      // we use the frozen entry for symmetry with `execute`.
      val skip             = frozenCtx.publishSkipFrozen.contains(true) || frozenCtx.skipPublish
      val checks: IO[Unit] =
        if (skip) IO.unit
        else
          IO.blocking(SbtRuntime.getSetting(frozenCtx.state, releaseIOPublishChecks)).flatMap {
            case false => IO.unit
            case true  =>
              // Evaluate publish/skip and publishTo against a transient state with the
              // resolved release version overlaid, so version-dependent skip patterns
              // (`publish / skip := isSnapshot.value`) are checked against the same
              // version state that publish-artifacts.execute will see. The transient
              // state is discarded — see ReleaseVersionWorkflow.withReleaseVersionOverlay.
              ReleaseVersionWorkflow.withReleaseVersionOverlay(frozenCtx) { tempState =>
                for {
                  allRefs <- publishTargetRefs(tempState)
                  missing <- allRefs.foldLeft(IO.pure(Vector.empty[ProjectRef])) { (ioAcc, ref) =>
                               ioAcc.flatMap { acc =>
                                 checkPublishSkip(ref, tempState).flatMap { skipped =>
                                   if (skipped) IO.pure(acc)
                                   else
                                     checkPublishToMissing(ref, tempState).map { missing =>
                                       if (missing) acc :+ ref else acc
                                     }
                                 }
                               }
                             }
                  result  <- {
                    val names = missing.map(_.project)
                    PublishValidation.requirePublishTarget(names.mkString(", "))(
                      publishSkipped = false,
                      publishToEmpty = missing.nonEmpty
                    )
                  }
                } yield result
              }
          }
      checks.as(frozenCtx)
    }),
    enableCrossBuild = true
  )

  val runTests: Step = ProcessStep.Single(
    name = "run-tests",
    execute = ctx =>
      if (ctx.skipTests) {
        IO.blocking(ctx.state.log.info(s"${ReleaseLogPrefixes.Core} Skipping tests")).as(ctx)
      } else {
        IO.blocking {
          val extracted = SbtRuntime.extracted(ctx.state)
          val ref       = extracted.get(thisProjectRef)
          val newState  = extracted.runAggregated(ref / Test / ReleaseIOCompat.testKey, ctx.state)
          failOnSbtTaskFailure(
            ctx,
            newState,
            s"run-tests: sbt task 'Test / ${ReleaseIOCompat.testKey.key.label}' " +
              "reported failure via FailureCommand"
          )
        }
      },
    enableCrossBuild = true
  )

  val runClean: Step = ProcessStep.Single(
    name = "run-clean",
    execute = ctx =>
      IO.blocking {
        val extracted = SbtRuntime.extracted(ctx.state)
        val ref       = extracted.get(thisProjectRef)
        val newState  = CleanCompat.runBuild(ctx.state, ref)
        failOnSbtTaskFailure(
          ctx,
          newState,
          "run-clean: clean action reported failure via FailureCommand"
        )
      }
  )

  /** Execute-time variant of [[shouldRunPublishHooks]]: evaluates per-project
    * `publish / skip` directly against the current state without applying the release-
    * version overlay. Used inside `publish-artifacts.execute` to decide whether to skip
    * the publish task when a `before-publish` hook installed `publish / skip := true`
    * via session settings on the post-hook state.
    *
    * Returns the threaded post-task state so that any `FailureCommand` armed by a
    * task-valued `publish / skip` is honored by the caller. Specifically:
    *   - Raises if the task arms sbt's FailureCommand sentinel (via `runTaskChecked`).
    *   - Falls back to "skip = false" with a warning for `undefined` / evaluation
    *     errors, matching the original lenient behavior of [[checkPublishSkip]].
    */
  private def anyTargetWillPublishPropagating(
      state: State,
      actionName: String
  ): IO[(State, Boolean)] =
    publishTargetRefs(state).flatMap { refs =>
      refs.foldLeft(IO.pure((state, false))) { (ioAcc, ref) =>
        ioAcc.flatMap {
          case (currentState, true)  => IO.pure((currentState, true))
          case (currentState, false) =>
            runTaskChecked(currentState, ref / publish / Keys.skip, actionName)
              .map { case (nextState, skipped) => (nextState, !skipped) }
              .handleErrorWith {
                case e: IllegalStateException if e.getMessage.contains("FailureCommand") =>
                  IO.raiseError(e)
                case NonFatal(e)                                                         =>
                  IO.blocking {
                    currentState.log.warn(
                      s"${ReleaseLogPrefixes.Core} Failed to evaluate publish / skip for " +
                        s"${ref.project}: ${errorMessage(e)}. Assuming skip = false."
                    )
                    (currentState, true) // skip=false → publish runs (matches checkPublishSkip)
                  }
              }
        }
      }
    }

  private[release] def shouldRunPublishHooks(ctx: ReleaseContext): IO[Boolean] =
    if (ctx.skipPublish) IO.pure(false)
    else
      // Evaluate the gate against the post-`set-release-version` state so
      // version-dependent skip patterns (`publish / skip := isSnapshot.value`)
      // produce the right decision when the frozen-gate cache is populated at
      // validate time. State is transient — see withReleaseVersionOverlay.
      ReleaseVersionWorkflow.withReleaseVersionOverlay(ctx) { tempState =>
        anyTargetWillPublish(tempState)
      }

  /** Execute-time variant of [[shouldRunPublishHooks]] for hook-narrow predicates.
    * Evaluates `publish / skip` directly against `ctx.state` without applying a fresh
    * release-version overlay, because at execute time the version is already pinned
    * via `appendSessionSettings` (`session.rawAppend`). Re-applying an overlay through
    * `appendWithSession` would re-derive `structure` from `session.mergeSettings` and
    * **drop transient settings** that an earlier execute hook installed via the public
    * `Project.extract(state).appendWithSession(...)` API (the documented pattern, see
    * the `hook-installed-publish-skip` scripted test). Used by `before-publish` so its
    * narrow stays consistent with `publish-artifacts.execute`'s own decision.
    */
  private[release] def shouldRunPublishHooksAtExecute(ctx: ReleaseContext): IO[Boolean] =
    if (ctx.skipPublish) IO.pure(false)
    else anyTargetWillPublish(ctx.state)

  private def anyTargetWillPublish(state: State): IO[Boolean] =
    publishTargetRefs(state).flatMap { refs =>
      refs.foldLeft(IO.pure(false)) { (ioShouldRun, ref) =>
        ioShouldRun.flatMap {
          case true  => IO.pure(true)
          case false => checkPublishSkip(ref, state).map(skipped => !skipped)
        }
      }
    }

  /** Resolve the projects that `runAggregated` will actually execute for the publish task.
    * Checks `aggregationEnabled` at each level so the expansion matches sbt's
    * `runAggregated` behavior when an intermediate project sets `aggregate := false`.
    */
  private def effectiveAggregates[A](
      extracted: Extracted,
      taskKey: TaskKey[A]
  ): Seq[ProjectRef] = {
    val data    = extracted.structure.data
    val rootKey = (extracted.currentRef / taskKey).scopedKey
    val enabled = sbt.internal.Aggregation.aggregationEnabled(rootKey, data)
    if (!enabled) Seq(extracted.currentRef)
    else {
      val units                                           = extracted.structure.units
      def resolve(ref: ProjectRef): Seq[ProjectRef]       = {
        val project = units.get(ref.build).flatMap(_.defined.get(ref.project))
        project.map(_.aggregate).getOrElse(Seq.empty)
      }
      def aggregationEnabledFor(ref: ProjectRef): Boolean =
        sbt.internal.Aggregation.aggregationEnabled((ref / taskKey).scopedKey, data)
      def loop(
          refs: Seq[ProjectRef],
          visited: Set[ProjectRef]
      ): (Seq[ProjectRef], Set[ProjectRef])               =
        refs.foldLeft((Seq.empty[ProjectRef], visited)) { case ((acc, vis), ref) =>
          if (vis.contains(ref)) (acc, vis)
          else if (!aggregationEnabledFor(ref)) (acc :+ ref, vis + ref)
          else {
            val (childAcc, childVis) = loop(resolve(ref), vis + ref)
            (acc ++ (ref +: childAcc), childVis)
          }
        }
      extracted.currentRef +: loop(resolve(extracted.currentRef), Set(extracted.currentRef))._1
    }
  }

  private def publishTargetRefs(state: State): IO[Seq[ProjectRef]] =
    IO.blocking {
      val extracted = SbtRuntime.extracted(state)
      effectiveAggregates(extracted, releaseIOPublishAction)
    }

  private def checkPublishSkip(
      ref: ProjectRef,
      state: State
  ): IO[Boolean] =
    runTaskChecked(
      state,
      ref / publish / Keys.skip,
      s"publish-artifacts: sbt task '${(publish / Keys.skip).key.label}'"
    ).map(_._2)
      .handleErrorWith {
        case e: IllegalStateException if e.getMessage.contains("FailureCommand") =>
          IO.raiseError(e)
        case NonFatal(e)                                                         =>
          IO.blocking {
            state.log.warn(
              s"${ReleaseLogPrefixes.Core} Failed to evaluate publish / skip for ${ref.project}: " +
                s"${errorMessage(e)}. Assuming skip = false."
            )
            false
          }
        case fatal                                                               =>
          IO.raiseError(fatal)
      }

  private def checkPublishToMissing(
      ref: ProjectRef,
      state: State
  ): IO[Boolean] =
    runTaskChecked(
      state,
      ref / publishTo,
      s"publish-artifacts: sbt task '${publishTo.key.label}'"
    ).map(_._2.isEmpty)
      .handleErrorWith {
        case e: IllegalStateException if e.getMessage.contains("FailureCommand") =>
          IO.raiseError(e)
        case NonFatal(e)                                                         =>
          IO.blocking {
            state.log.warn(
              s"${ReleaseLogPrefixes.Core} Failed to evaluate publishTo for ${ref.project}: " +
                s"${errorMessage(e)}. Assuming publishTo is missing."
            )
            true
          }
        case fatal                                                               =>
          IO.raiseError(fatal)
      }
}
