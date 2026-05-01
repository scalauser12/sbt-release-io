package io.release.monorepo.internal

import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.MonorepoStepAliases.GlobalStep
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.steps.MonorepoPublishSteps
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.runtime.HookPhases
import io.release.runtime.engine.LifecycleCompiler
import io.release.runtime.workflow.DecisionResolver
import sbt.*

/** Canonical monorepo lifecycle order and hook compilation. */
private[release] object MonorepoLifecycle {

  private case class GlobalHookPhaseConfig(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[
        MonorepoGlobalHookIO
      ],
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      narrowExecute: Option[MonorepoContext => IO[Boolean]] = None
  )

  private case class ProjectHookPhaseConfig(
      phase: String,
      narrowExecute: Option[(MonorepoContext, ProjectReleaseInfo) => IO[Boolean]] = None,
      resolveHooks: MonorepoHookConfiguration => Seq[
        MonorepoProjectHookIO
      ],
      gate: (MonorepoContext, ProjectReleaseInfo) => IO[
        Boolean
      ] = (_, _) => IO.pure(true),
      crossBuild: Boolean = false,
      freezeGate: Boolean = false,
      gateKey: Option[(MonorepoContext, ProjectReleaseInfo) => String] = None,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  )

  private type Phase =
    LifecycleCompiler.Phase[
      MonorepoHookConfiguration,
      MonorepoContext,
      ProjectReleaseInfo
    ]

  private def singleBuiltIn(
      step: GlobalStep,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.singleBuiltIn(
      step = step,
      enabled = enabled
    )

  private def perItemBuiltIn(
      step: ProjectStep,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.perItemBuiltIn(
      step = step,
      enabled = enabled
    )

  private def globalHookPhase(
      config: GlobalHookPhaseConfig
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = config.phase,
      resolveHooks = config.resolveHooks,
      gate = _ => IO.pure(true),
      nameOf = (hook: MonorepoGlobalHookIO) => hook.name,
      executeOf = (hook: MonorepoGlobalHookIO) => hook.execute,
      executeTrackedOf =
        Some((hook: MonorepoGlobalHookIO) => MonorepoGlobalHookIO.trackedExecute(hook)),
      validateOf = (hook: MonorepoGlobalHookIO) => hook.validate,
      enabled = config.enabled,
      narrowExecute = config.narrowExecute
    )

  private def projectHookPhase(
      config: ProjectHookPhaseConfig
  ): Phase =
    LifecycleCompiler.perItemHookPhase(
      phase = config.phase,
      resolveHooks = config.resolveHooks,
      gate = config.gate,
      nameOf = (hook: MonorepoProjectHookIO) => hook.name,
      executeOf = (hook: MonorepoProjectHookIO) => hook.execute,
      executeTrackedOf =
        Some((hook: MonorepoProjectHookIO) => MonorepoProjectHookIO.trackedExecute(hook)),
      validateOf = (hook: MonorepoProjectHookIO) => hook.validate,
      crossBuild = config.crossBuild,
      freezeGate = config.freezeGate,
      gateKey = config.gateKey,
      enabled = config.enabled,
      narrowExecute = config.narrowExecute
    )

  private val publishGate: (MonorepoContext, ProjectReleaseInfo) => IO[
    Boolean
  ] =
    MonorepoPublishSteps.shouldRunPublishHooks

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
      config.enableTagging &&
        !config.beforeReleaseVersionWriteHooks.exists(_.mayChangeTagSettings) &&
        !config.afterReleaseVersionWriteHooks.exists(_.mayChangeTagSettings) &&
        !config.beforeReleaseCommitHooks.exists(_.mayChangeTagSettings) &&
        !config.afterReleaseCommitHooks.exists(_.mayChangeTagSettings) &&
        !config.beforeTagHooks.exists(_.mayChangeTagSettings)

  /** Execute-time AND condition for the global `after-push` hook: fires only
    * when the monorepo `push-changes` step actually pushed. Validation is
    * unaffected so `releaseIOMonorepo check` still rehearses the hook code.
    */
  private val afterPushNarrow: MonorepoContext => IO[Boolean] =
    ctx => IO.pure(ctx.pushExecuted)

  // @formatter:off
  private val afterCleanCheck = GlobalHookPhaseConfig(
    phase = HookPhases.AfterCleanCheck,
    resolveHooks = _.afterCleanCheckHooks
  )
  private val beforeSelection = GlobalHookPhaseConfig(
    phase = HookPhases.BeforeSelection,
    resolveHooks = _.beforeSelectionHooks
  )
  private val afterSelection = GlobalHookPhaseConfig(
    phase = HookPhases.AfterSelection,
    resolveHooks = _.afterSelectionHooks
  )
  private val beforeVersionResolution = ProjectHookPhaseConfig(
    phase = HookPhases.BeforeVersionResolution,
    resolveHooks = _.beforeVersionResolutionHooks,
    crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
  )
  private val afterVersionResolution = ProjectHookPhaseConfig(
    phase = HookPhases.AfterVersionResolution,
    resolveHooks = _.afterVersionResolutionHooks,
    crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
  )
  private val beforeReleaseVersionWrite = ProjectHookPhaseConfig(
    phase = HookPhases.BeforeReleaseVersionWrite,
    resolveHooks = _.beforeReleaseVersionWriteHooks,
    crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
  )
  private val afterReleaseVersionWrite = ProjectHookPhaseConfig(
    phase = HookPhases.AfterReleaseVersionWrite,
    resolveHooks = _.afterReleaseVersionWriteHooks,
    crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
  )
  private val beforeReleaseCommit = GlobalHookPhaseConfig(
    phase = HookPhases.BeforeReleaseCommit,
    resolveHooks = _.beforeReleaseCommitHooks
  )
  private val afterReleaseCommit = GlobalHookPhaseConfig(
    phase = HookPhases.AfterReleaseCommit,
    resolveHooks = _.afterReleaseCommitHooks
  )
  private val beforeTag = ProjectHookPhaseConfig(
    phase = HookPhases.BeforeTag,
    resolveHooks = _.beforeTagHooks,
    enabled = _.enableTagging
  )
  private val afterTag = ProjectHookPhaseConfig(
    phase = HookPhases.AfterTag,
    resolveHooks = _.afterTagHooks,
    enabled = _.enableTagging
  )
  private val beforePublish = ProjectHookPhaseConfig(
    phase = HookPhases.BeforePublish,
    resolveHooks = _.beforePublishHooks,
    gate = publishGate,
    crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
    freezeGate = true,
    gateKey = Some(MonorepoPublishSteps.publishGateKey),
    enabled = _.enablePublish
  )
  private val afterPublish = ProjectHookPhaseConfig(
    phase = HookPhases.AfterPublish,
    resolveHooks = _.afterPublishHooks,
    gate = publishGate,
    crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
    freezeGate = true,
    gateKey = Some(MonorepoPublishSteps.publishGateKey),
    enabled = _.enablePublish,
    narrowExecute = Some(afterPublishNarrow)
  )
  private val beforeNextVersionWrite = ProjectHookPhaseConfig(
    phase = HookPhases.BeforeNextVersionWrite,
    resolveHooks = _.beforeNextVersionWriteHooks,
    crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
  )
  private val afterNextVersionWrite = ProjectHookPhaseConfig(
    phase = HookPhases.AfterNextVersionWrite,
    resolveHooks = _.afterNextVersionWriteHooks,
    crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
  )
  private val beforeNextCommit = GlobalHookPhaseConfig(
    phase = HookPhases.BeforeNextCommit,
    resolveHooks = _.beforeNextCommitHooks
  )
  private val afterNextCommit = GlobalHookPhaseConfig(
    phase = HookPhases.AfterNextCommit,
    resolveHooks = _.afterNextCommitHooks
  )
  private val beforePush = GlobalHookPhaseConfig(
    phase = HookPhases.BeforePush,
    resolveHooks = _.beforePushHooks,
    enabled = _.enablePush,
    narrowExecute = Some(beforePushNarrow)
  )
  private val afterPush = GlobalHookPhaseConfig(
    phase = HookPhases.AfterPush,
    resolveHooks = _.afterPushHooks,
    enabled = _.enablePush,
    narrowExecute = Some(afterPushNarrow)
  )
  // @formatter:on

  private[release] lazy val phases: Seq[Phase] = Seq(
    singleBuiltIn(MonorepoReleaseSteps.initializeVcs),
    singleBuiltIn(
      MonorepoReleaseSteps.checkCleanWorkingDir
    ),
    globalHookPhase(afterCleanCheck),
    singleBuiltIn(
      MonorepoReleaseSteps.resolveReleaseOrder
    ),
    globalHookPhase(beforeSelection),
    singleBuiltIn(
      MonorepoReleaseSteps.detectOrSelectProjects
    ),
    globalHookPhase(afterSelection),
    perItemBuiltIn(
      MonorepoReleaseSteps.checkSnapshotDependencies,
      _.enableSnapshotDependenciesCheck
    ),
    projectHookPhase(beforeVersionResolution),
    perItemBuiltIn(
      MonorepoReleaseSteps.inquireVersions
    ),
    projectHookPhase(afterVersionResolution),
    perItemBuiltIn(
      MonorepoReleaseSteps.tagPreflight,
      tagPreflightEnabled
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.runClean,
      _.enableRunClean
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.runTests,
      _.enableRunTests
    ),
    projectHookPhase(beforeReleaseVersionWrite),
    perItemBuiltIn(
      MonorepoReleaseSteps.setReleaseVersions
    ),
    projectHookPhase(afterReleaseVersionWrite),
    globalHookPhase(beforeReleaseCommit),
    singleBuiltIn(
      MonorepoReleaseSteps.commitReleaseVersions
    ),
    globalHookPhase(afterReleaseCommit),
    projectHookPhase(beforeTag),
    perItemBuiltIn(
      MonorepoReleaseSteps.tagReleasesPerProject,
      _.enableTagging
    ),
    projectHookPhase(afterTag),
    projectHookPhase(beforePublish),
    perItemBuiltIn(
      MonorepoReleaseSteps.publishArtifacts,
      _.enablePublish
    ),
    projectHookPhase(afterPublish),
    projectHookPhase(beforeNextVersionWrite),
    perItemBuiltIn(
      MonorepoReleaseSteps.setNextVersions
    ),
    projectHookPhase(afterNextVersionWrite),
    globalHookPhase(beforeNextCommit),
    singleBuiltIn(
      MonorepoReleaseSteps.commitNextVersions
    ),
    globalHookPhase(afterNextCommit),
    globalHookPhase(beforePush),
    singleBuiltIn(
      MonorepoReleaseSteps.pushChanges,
      _.enablePush
    ),
    globalHookPhase(afterPush)
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
