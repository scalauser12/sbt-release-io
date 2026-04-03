package io.release.monorepo

import cats.effect.IO
import io.release.internal.HookStepCompilation
import io.release.internal.LifecycleCompiler
import io.release.internal.ProcessStep
import io.release.monorepo.steps.{MonorepoPublishSteps, MonorepoReleaseSteps}

/** Canonical monorepo lifecycle order and hook compilation. */
private[monorepo] object MonorepoLifecycle {

  private type ProjectGate = (MonorepoContext, ProjectReleaseInfo) => IO[Boolean]
  private type Phase       =
    LifecycleCompiler.Phase[MonorepoHookConfiguration, MonorepoContext, ProjectReleaseInfo]

  private val AlwaysGlobal: MonorepoContext => IO[Boolean] = _ => IO.pure(true)
  private val AlwaysProject: ProjectGate                   = (_, _) => IO.pure(true)
  private val PublishProject: ProjectGate                  =
    MonorepoPublishSteps.shouldRunPublishHooks

  private def publishCachedGate(
      phase: String
  ): HookStepCompilation.CachedItemGate[
    MonorepoContext,
    ProjectReleaseInfo,
    MonorepoPublishHookGateCache.HookToken
  ] =
    HookStepCompilation
      .CachedItemGate[
        MonorepoContext,
        ProjectReleaseInfo,
        MonorepoPublishHookGateCache.HookToken
      ](
        tokenForIndex = hookIndex => MonorepoPublishHookGateCache.HookToken(phase, hookIndex),
        resolveDecision = (ctx, token, project, decision) =>
          MonorepoPublishHookGateCache.resolveDecision(ctx, token, project, decision),
        snapshotDecision = (ctx, token, project, evaluateGate) =>
          MonorepoPublishHookGateCache.snapshotDecision(
            ctx,
            token,
            project,
            evaluateGate
          )
      )

  private def singleBuiltIn(
      step: ProcessStep.Single[MonorepoContext],
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.singleBuiltIn(
      step = step,
      enabled = enabled
    )

  private def perItemBuiltIn(
      step: ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo],
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.perItemBuiltIn(
      step = step,
      enabled = enabled
    )

  private def globalHookPhase(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[MonorepoGlobalHookIO],
      gate: MonorepoContext => IO[Boolean],
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = gate,
      nameOf = (hook: MonorepoGlobalHookIO) => hook.name,
      executeOf = (hook: MonorepoGlobalHookIO) => hook.execute,
      validateOf = (hook: MonorepoGlobalHookIO) => hook.validate,
      enabled = enabled
    )

  private def projectHookPhase(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[MonorepoProjectHookIO],
      gate: ProjectGate,
      crossBuild: Boolean = false,
      cachedGate: Option[
        HookStepCompilation.CachedItemGate[
          MonorepoContext,
          ProjectReleaseInfo,
          MonorepoPublishHookGateCache.HookToken
        ]
      ] = None,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.perItemHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = gate,
      nameOf = (hook: MonorepoProjectHookIO) => hook.name,
      executeOf = (hook: MonorepoProjectHookIO) => hook.execute,
      validateOf = (hook: MonorepoProjectHookIO) => hook.validate,
      crossBuild = crossBuild,
      cachedGate = cachedGate,
      enabled = enabled
    )

  private val phases: Seq[Phase] = Seq(
    singleBuiltIn(MonorepoReleaseSteps.initializeVcs),
    singleBuiltIn(MonorepoReleaseSteps.checkCleanWorkingDir),
    globalHookPhase(
      phase = "after-clean-check",
      resolveHooks = _.afterCleanCheckHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.resolveReleaseOrder),
    globalHookPhase(
      phase = "before-selection",
      resolveHooks = _.beforeSelectionHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.detectOrSelectProjects),
    globalHookPhase(
      phase = "after-selection",
      resolveHooks = _.afterSelectionHooks,
      gate = AlwaysGlobal
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.checkSnapshotDependencies,
      _.enableSnapshotDependenciesCheck
    ),
    projectHookPhase(
      phase = "before-version-resolution",
      resolveHooks = _.beforeVersionResolutionHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.inquireVersions),
    projectHookPhase(
      phase = "after-version-resolution",
      resolveHooks = _.afterVersionResolutionHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.runClean, _.enableRunClean),
    perItemBuiltIn(MonorepoReleaseSteps.runTests, _.enableRunTests),
    projectHookPhase(
      phase = "before-release-version-write",
      resolveHooks = _.beforeReleaseVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.setReleaseVersions),
    projectHookPhase(
      phase = "after-release-version-write",
      resolveHooks = _.afterReleaseVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ),
    globalHookPhase(
      phase = "before-release-commit",
      resolveHooks = _.beforeReleaseCommitHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.commitReleaseVersions),
    globalHookPhase(
      phase = "after-release-commit",
      resolveHooks = _.afterReleaseCommitHooks,
      gate = AlwaysGlobal
    ),
    projectHookPhase(
      phase = "before-tag",
      resolveHooks = _.beforeTagHooks,
      gate = AlwaysProject,
      enabled = _.enableTagging
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.tagReleasesPerProject,
      _.enableTagging
    ),
    projectHookPhase(
      phase = "after-tag",
      resolveHooks = _.afterTagHooks,
      gate = AlwaysProject,
      enabled = _.enableTagging
    ),
    projectHookPhase(
      phase = "before-publish",
      resolveHooks = _.beforePublishHooks,
      gate = PublishProject,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("before-publish")),
      enabled = _.enablePublish
    ),
    perItemBuiltIn(MonorepoReleaseSteps.publishArtifacts, _.enablePublish),
    projectHookPhase(
      phase = "after-publish",
      resolveHooks = _.afterPublishHooks,
      gate = PublishProject,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("after-publish")),
      enabled = _.enablePublish
    ),
    projectHookPhase(
      phase = "before-next-version-write",
      resolveHooks = _.beforeNextVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.setNextVersions),
    projectHookPhase(
      phase = "after-next-version-write",
      resolveHooks = _.afterNextVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ),
    globalHookPhase(
      phase = "before-next-commit",
      resolveHooks = _.beforeNextCommitHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.commitNextVersions),
    globalHookPhase(
      phase = "after-next-commit",
      resolveHooks = _.afterNextCommitHooks,
      gate = AlwaysGlobal
    ),
    globalHookPhase(
      phase = "before-push",
      resolveHooks = _.beforePushHooks,
      gate = AlwaysGlobal,
      enabled = _.enablePush
    ),
    singleBuiltIn(MonorepoReleaseSteps.pushChanges, _.enablePush),
    globalHookPhase(
      phase = "after-push",
      resolveHooks = _.afterPushHooks,
      gate = AlwaysGlobal,
      enabled = _.enablePush
    )
  )

  val defaults: Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]] =
    LifecycleCompiler.defaults(phases)

  def compile(
      hooks: MonorepoHookConfiguration
  ): Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]] =
    LifecycleCompiler.compile(hooks, phases)
}
