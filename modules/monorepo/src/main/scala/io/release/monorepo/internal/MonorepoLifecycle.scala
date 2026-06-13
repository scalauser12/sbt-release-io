package io.release.monorepo.internal

import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.steps.MonorepoPublishSteps
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.runtime.HookPhases
import io.release.runtime.engine.LifecycleCompiler
import io.release.runtime.preflight.PreflightPhaseGroups
import io.release.runtime.workflow.DecisionResolver
import sbt.*

/** Canonical monorepo lifecycle order and hook compilation. */
private[release] object MonorepoLifecycle {

  private type Phase =
    LifecycleCompiler.Phase[
      MonorepoHookConfiguration,
      MonorepoContext,
      ProjectReleaseInfo
    ]

  // Both GlobalStep (ProcessStep.Single[MonorepoContext], i.e. ProcessStep[_, Nothing]) and
  // ProjectStep (ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo]) are subtypes of
  // AnyStep = ProcessStep[MonorepoContext, ProjectReleaseInfo] (ProcessStep is covariant in I),
  // so a single builtIn wrapper covers both kinds of built-in step.
  private def builtIn(
      step: AnyStep,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.builtIn(
      step = step,
      enabled = enabled
    )

  private def globalHookPhase(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[MonorepoGlobalHookIO],
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      narrowExecute: Option[MonorepoContext => IO[Boolean]] = None
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = _ => IO.pure(true),
      nameOf = (hook: MonorepoGlobalHookIO) => hook.name,
      executeOf = (hook: MonorepoGlobalHookIO) => hook.execute,
      executeTrackedOf =
        Some((hook: MonorepoGlobalHookIO) => MonorepoGlobalHookIO.trackedExecute(hook)),
      validateOf = (hook: MonorepoGlobalHookIO) => hook.validate,
      enabled = enabled,
      narrowExecute = narrowExecute
    )

  private def projectHookPhase(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[MonorepoProjectHookIO],
      gate: (MonorepoContext, ProjectReleaseInfo) => IO[Boolean] = (_, _) => IO.pure(true),
      crossBuild: Boolean = false,
      freezeGateKey: Option[(MonorepoContext, ProjectReleaseInfo) => String] = None,
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      narrowExecute: Option[(MonorepoContext, ProjectReleaseInfo) => IO[Boolean]] = None
  ): Phase =
    LifecycleCompiler.perItemHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = gate,
      nameOf = (hook: MonorepoProjectHookIO) => hook.name,
      executeOf = (hook: MonorepoProjectHookIO) => hook.execute,
      executeTrackedOf =
        Some((hook: MonorepoProjectHookIO) => MonorepoProjectHookIO.trackedExecute(hook)),
      validateOf = (hook: MonorepoProjectHookIO) => hook.validate,
      crossBuild = crossBuild,
      freezeGateKey = freezeGateKey,
      enabled = enabled,
      narrowExecute = narrowExecute
    )

  private val publishGate: (MonorepoContext, ProjectReleaseInfo) => IO[
    Boolean
  ] =
    MonorepoPublishSteps.shouldRunPublishHooks

  /** Execute-time AND condition for `before-publish` hooks: combined with the
    * frozen validate-time gate decision so the hook fires only when the
    * publish task will actually run for this project/iteration at execute
    * time. Re-evaluates `ctx.skipPublish` (folded in via
    * [[MonorepoPublishSteps.shouldRunPublishHooksAtExecute]]'s `effectiveSkip`
    * check) and per-project `publish / skip` against `ctx.state` so a hook
    * earlier in the release (e.g., `afterReleaseCommit`, `afterTag`) that
    * flipped `ctx.skipPublish` or installed `publish / skip := true` via
    * session settings suppresses the before-publish phase symmetrically with
    * [[afterPublishNarrow]]. Validation is unaffected (uses the cached upper
    * bound), preserving the validate-before-execute contract for
    * `releaseIOMonorepo check`.
    */
  private val beforePublishNarrow: (MonorepoContext, ProjectReleaseInfo) => IO[Boolean] =
    MonorepoPublishSteps.shouldRunPublishHooksAtExecute

  /** Execute-time AND condition for `after-publish` hooks: combined with the
    * frozen validate-time gate decision so the hook fires only when the
    * publish task actually ran for this project/iteration. Validation is
    * unaffected (uses the cached upper bound), preserving the
    * validate-before-execute contract for `releaseIOMonorepo check`.
    */
  private val afterPublishNarrow: (MonorepoContext, ProjectReleaseInfo) => IO[Boolean] =
    (ctx, project) =>
      IO.pure(
        ctx.publishExecutedKeys
          .exists(_.contains(MonorepoPublishSteps.publishGateKey(ctx, project)))
      )

  /** Execute-time AND condition for the global `before-push` hook: fires only
    * when the push decision is not already a deterministic decline. Mirrors the
    * core lifecycle's `beforePushNarrow` — `Some(false)` answers and
    * non-interactive no-default releases suppress the hook because push is
    * guaranteed to take its decline branch; interactive prompts remain
    * observed because the operator may still answer either way. Validation
    * rehearses the hook unconditionally so `releaseIOMonorepo check` still
    * exercises hook code.
    */
  private val beforePushNarrow: MonorepoContext => IO[Boolean] =
    ctx => IO.pure(!DecisionResolver.effectivelyDeclinedPush(ctx))

  /** Auto-disable `tag-preflight` whenever any intervening hook between version
    * resolution and tag creation opts in to `mayChangeTagSettings`. Those hooks
    * (`beforeReleaseVersionWrite`, `afterReleaseVersionWrite`,
    * `beforeReleaseCommit`, `afterReleaseCommit`, `beforeTag`) can rewrite
    * `releaseIOMonorepoVcsTagName` via session settings after the preflight
    * has already evaluated the default name. Running preflight on the
    * pre-hook name produces false-positive aborts when the hook's intended
    * tag is free but the default tag conflicts.
    *
    * Hooks default to `mayChangeTagSettings = false`, so unflagged hooks (the
    * dominant case — logging, signing, changelog updates) keep the early-abort
    * preflight active. Hook authors that actually rewrite tag settings opt in
    * by constructing the hook with `.copy(mayChangeTagSettings = true)`,
    * accepting the late `beforeCreateTag` check inside `tag-releases` in
    * exchange for the post-hook tag name being authoritative. To re-enable
    * the early preflight without the opt-out, move tag-name-affecting logic
    * to `afterVersionResolution`, which runs before `tag-preflight`.
    */
  private val tagPreflightEnabled: MonorepoHookConfiguration => Boolean =
    config =>
      PreflightPhaseGroups.tagPreflightEnabled(
        config.enableTagging,
        config.beforeReleaseVersionWriteHooks.exists(_.mayChangeTagSettings),
        config.afterReleaseVersionWriteHooks.exists(_.mayChangeTagSettings),
        config.beforeReleaseCommitHooks.exists(_.mayChangeTagSettings),
        config.afterReleaseCommitHooks.exists(_.mayChangeTagSettings),
        config.beforeTagHooks.exists(_.mayChangeTagSettings)
      )

  /** Execute-time AND condition for the global `after-push` hook: fires only
    * when the monorepo `push-changes` step actually pushed. Validation is
    * unaffected so `releaseIOMonorepo check` still rehearses the hook code.
    */
  private val afterPushNarrow: MonorepoContext => IO[Boolean] =
    ctx => IO.pure(ctx.pushExecuted)

  private[release] lazy val phases: Seq[Phase] = Seq(
    builtIn(MonorepoReleaseSteps.initializeVcs),
    builtIn(MonorepoReleaseSteps.checkCleanWorkingDir),
    globalHookPhase(HookPhases.AfterCleanCheck, _.afterCleanCheckHooks),
    builtIn(MonorepoReleaseSteps.resolveReleaseOrder),
    globalHookPhase(HookPhases.BeforeSelection, _.beforeSelectionHooks),
    builtIn(MonorepoReleaseSteps.detectOrSelectProjects),
    globalHookPhase(HookPhases.AfterSelection, _.afterSelectionHooks),
    builtIn(
      MonorepoReleaseSteps.checkSnapshotDependencies,
      _.enableSnapshotDependenciesCheck
    ),
    projectHookPhase(
      HookPhases.BeforeVersionResolution,
      _.beforeVersionResolutionHooks,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ),
    builtIn(MonorepoReleaseSteps.inquireVersions),
    projectHookPhase(
      HookPhases.AfterVersionResolution,
      _.afterVersionResolutionHooks,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ),
    builtIn(MonorepoReleaseSteps.tagPreflight, tagPreflightEnabled),
    builtIn(MonorepoReleaseSteps.runClean, _.enableRunClean),
    builtIn(MonorepoReleaseSteps.runTests, _.enableRunTests),
    projectHookPhase(
      HookPhases.BeforeReleaseVersionWrite,
      _.beforeReleaseVersionWriteHooks,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ),
    builtIn(MonorepoReleaseSteps.setReleaseVersions),
    projectHookPhase(
      HookPhases.AfterReleaseVersionWrite,
      _.afterReleaseVersionWriteHooks,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ),
    globalHookPhase(HookPhases.BeforeReleaseCommit, _.beforeReleaseCommitHooks),
    builtIn(MonorepoReleaseSteps.commitReleaseVersions),
    globalHookPhase(HookPhases.AfterReleaseCommit, _.afterReleaseCommitHooks),
    projectHookPhase(HookPhases.BeforeTag, _.beforeTagHooks, enabled = _.enableTagging),
    builtIn(MonorepoReleaseSteps.tagReleasesPerProject, _.enableTagging),
    projectHookPhase(HookPhases.AfterTag, _.afterTagHooks, enabled = _.enableTagging),
    projectHookPhase(
      HookPhases.BeforePublish,
      _.beforePublishHooks,
      gate = publishGate,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      freezeGateKey = Some(MonorepoPublishSteps.publishGateKey),
      enabled = _.enablePublish,
      narrowExecute = Some(beforePublishNarrow)
    ),
    builtIn(MonorepoReleaseSteps.publishArtifacts, _.enablePublish),
    projectHookPhase(
      HookPhases.AfterPublish,
      _.afterPublishHooks,
      gate = publishGate,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      freezeGateKey = Some(MonorepoPublishSteps.publishGateKey),
      enabled = _.enablePublish,
      narrowExecute = Some(afterPublishNarrow)
    ),
    projectHookPhase(
      HookPhases.BeforeNextVersionWrite,
      _.beforeNextVersionWriteHooks,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ),
    builtIn(MonorepoReleaseSteps.setNextVersions),
    projectHookPhase(
      HookPhases.AfterNextVersionWrite,
      _.afterNextVersionWriteHooks,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ),
    globalHookPhase(HookPhases.BeforeNextCommit, _.beforeNextCommitHooks),
    builtIn(MonorepoReleaseSteps.commitNextVersions),
    globalHookPhase(HookPhases.AfterNextCommit, _.afterNextCommitHooks),
    globalHookPhase(
      HookPhases.BeforePush,
      _.beforePushHooks,
      enabled = _.enablePush,
      narrowExecute = Some(beforePushNarrow)
    ),
    builtIn(MonorepoReleaseSteps.pushChanges, _.enablePush),
    globalHookPhase(
      HookPhases.AfterPush,
      _.afterPushHooks,
      enabled = _.enablePush,
      narrowExecute = Some(afterPushNarrow)
    )
  )

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    MonorepoHookConfiguration.defaultSettings

  val defaults: Seq[AnyStep] =
    LifecycleCompiler.defaults(phases)

  def compile(
      hooks: MonorepoHookConfiguration
  ): IO[Seq[AnyStep]] =
    LifecycleCompiler.compile(hooks, phases)
}
