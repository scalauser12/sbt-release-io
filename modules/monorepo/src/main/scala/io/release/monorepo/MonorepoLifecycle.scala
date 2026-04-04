package io.release.monorepo

import cats.effect.IO
import io.release.internal.HookStepCompilation
import io.release.internal.LifecycleCompiler
import io.release.internal.LifecycleConfigCompiler
import io.release.internal.ProcessStep
import io.release.monorepo.steps.{MonorepoPublishSteps, MonorepoReleaseSteps}
import sbt.Setting

/** Canonical monorepo lifecycle order and hook compilation. */
private[release] object MonorepoLifecycle {

  private type ProjectGate     = (MonorepoContext, ProjectReleaseInfo) => IO[Boolean]
  private type Phase           =
    LifecycleCompiler.Phase[MonorepoHookConfiguration, MonorepoContext, ProjectReleaseInfo]
  private type Slot            = LifecycleConfigCompiler.Binding[MonorepoHookConfiguration]
  private type PolicySlot      = LifecycleConfigCompiler.PolicyBinding[MonorepoHookConfiguration]
  private type GlobalHookSlot  =
    LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoGlobalHookIO]
  private type ProjectHookSlot =
    LifecycleConfigCompiler.HookBinding[MonorepoHookConfiguration, MonorepoProjectHookIO]

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
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      slots: Seq[Slot] = Nil
  ): Phase =
    LifecycleCompiler.singleBuiltIn(
      step = step,
      enabled = enabled,
      configBindings = slots
    )

  private def singleBuiltIn(
      step: ProcessStep.Single[MonorepoContext],
      policySlot: PolicySlot
  ): Phase =
    singleBuiltIn(
      step = step,
      enabled = policySlot.enabled,
      slots = Seq(policySlot)
    )

  private def perItemBuiltIn(
      step: ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo],
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      slots: Seq[Slot] = Nil
  ): Phase =
    LifecycleCompiler.perItemBuiltIn(
      step = step,
      enabled = enabled,
      configBindings = slots
    )

  private def perItemBuiltIn(
      step: ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo],
      policySlot: PolicySlot
  ): Phase =
    perItemBuiltIn(
      step = step,
      enabled = policySlot.enabled,
      slots = Seq(policySlot)
    )

  private def globalHookPhase(
      phase: String,
      hookSlot: GlobalHookSlot,
      gate: MonorepoContext => IO[Boolean],
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      additionalSlots: Seq[Slot] = Nil
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = phase,
      resolveHooks = hookSlot.resolveHooks,
      gate = gate,
      nameOf = (hook: MonorepoGlobalHookIO) => hook.name,
      executeOf = (hook: MonorepoGlobalHookIO) => hook.execute,
      validateOf = (hook: MonorepoGlobalHookIO) => hook.validate,
      enabled = enabled,
      configBindings = hookSlot +: additionalSlots
    )

  private def projectHookPhase(
      phase: String,
      hookSlot: ProjectHookSlot,
      gate: ProjectGate,
      crossBuild: Boolean = false,
      cachedGate: Option[
        HookStepCompilation.CachedItemGate[
          MonorepoContext,
          ProjectReleaseInfo,
          MonorepoPublishHookGateCache.HookToken
        ]
      ] = None,
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      additionalSlots: Seq[Slot] = Nil
  ): Phase =
    LifecycleCompiler.perItemHookPhase(
      phase = phase,
      resolveHooks = hookSlot.resolveHooks,
      gate = gate,
      nameOf = (hook: MonorepoProjectHookIO) => hook.name,
      executeOf = (hook: MonorepoProjectHookIO) => hook.execute,
      validateOf = (hook: MonorepoProjectHookIO) => hook.validate,
      crossBuild = crossBuild,
      cachedGate = cachedGate,
      enabled = enabled,
      configBindings = hookSlot +: additionalSlots
    )

  private[release] val phases: Seq[Phase] = Seq(
    singleBuiltIn(MonorepoReleaseSteps.initializeVcs),
    singleBuiltIn(MonorepoReleaseSteps.checkCleanWorkingDir),
    globalHookPhase(
      phase = "after-clean-check",
      hookSlot = MonorepoGlobalHookSlots.afterCleanCheckHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.resolveReleaseOrder),
    globalHookPhase(
      phase = "before-selection",
      hookSlot = MonorepoGlobalHookSlots.beforeSelectionHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.detectOrSelectProjects),
    globalHookPhase(
      phase = "after-selection",
      hookSlot = MonorepoGlobalHookSlots.afterSelectionHooks,
      gate = AlwaysGlobal
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.checkSnapshotDependencies,
      MonorepoPolicySlots.enableSnapshotDependenciesCheck
    ),
    projectHookPhase(
      phase = "before-version-resolution",
      hookSlot = MonorepoProjectHookSlots.beforeVersionResolutionHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.inquireVersions),
    projectHookPhase(
      phase = "after-version-resolution",
      hookSlot = MonorepoProjectHookSlots.afterVersionResolutionHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.runClean, MonorepoPolicySlots.enableRunClean),
    perItemBuiltIn(MonorepoReleaseSteps.runTests, MonorepoPolicySlots.enableRunTests),
    projectHookPhase(
      phase = "before-release-version-write",
      hookSlot = MonorepoProjectHookSlots.beforeReleaseVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.setReleaseVersions),
    projectHookPhase(
      phase = "after-release-version-write",
      hookSlot = MonorepoProjectHookSlots.afterReleaseVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ),
    globalHookPhase(
      phase = "before-release-commit",
      hookSlot = MonorepoGlobalHookSlots.beforeReleaseCommitHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.commitReleaseVersions),
    globalHookPhase(
      phase = "after-release-commit",
      hookSlot = MonorepoGlobalHookSlots.afterReleaseCommitHooks,
      gate = AlwaysGlobal
    ),
    projectHookPhase(
      phase = "before-tag",
      hookSlot = MonorepoProjectHookSlots.beforeTagHooks,
      gate = AlwaysProject,
      enabled = MonorepoPolicySlots.enableTagging.enabled,
      additionalSlots = Seq(MonorepoPolicySlots.enableTagging)
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.tagReleasesPerProject,
      MonorepoPolicySlots.enableTagging
    ),
    projectHookPhase(
      phase = "after-tag",
      hookSlot = MonorepoProjectHookSlots.afterTagHooks,
      gate = AlwaysProject,
      enabled = MonorepoPolicySlots.enableTagging.enabled,
      additionalSlots = Seq(MonorepoPolicySlots.enableTagging)
    ),
    projectHookPhase(
      phase = "before-publish",
      hookSlot = MonorepoProjectHookSlots.beforePublishHooks,
      gate = PublishProject,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("before-publish")),
      enabled = MonorepoPolicySlots.enablePublish.enabled,
      additionalSlots = Seq(MonorepoPolicySlots.enablePublish)
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.publishArtifacts,
      MonorepoPolicySlots.enablePublish
    ),
    projectHookPhase(
      phase = "after-publish",
      hookSlot = MonorepoProjectHookSlots.afterPublishHooks,
      gate = PublishProject,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("after-publish")),
      enabled = MonorepoPolicySlots.enablePublish.enabled,
      additionalSlots = Seq(MonorepoPolicySlots.enablePublish)
    ),
    projectHookPhase(
      phase = "before-next-version-write",
      hookSlot = MonorepoProjectHookSlots.beforeNextVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.setNextVersions),
    projectHookPhase(
      phase = "after-next-version-write",
      hookSlot = MonorepoProjectHookSlots.afterNextVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ),
    globalHookPhase(
      phase = "before-next-commit",
      hookSlot = MonorepoGlobalHookSlots.beforeNextCommitHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.commitNextVersions),
    globalHookPhase(
      phase = "after-next-commit",
      hookSlot = MonorepoGlobalHookSlots.afterNextCommitHooks,
      gate = AlwaysGlobal
    ),
    globalHookPhase(
      phase = "before-push",
      hookSlot = MonorepoGlobalHookSlots.beforePushHooks,
      gate = AlwaysGlobal,
      enabled = MonorepoPolicySlots.enablePush.enabled,
      additionalSlots = Seq(MonorepoPolicySlots.enablePush)
    ),
    singleBuiltIn(MonorepoReleaseSteps.pushChanges, MonorepoPolicySlots.enablePush),
    globalHookPhase(
      phase = "after-push",
      hookSlot = MonorepoGlobalHookSlots.afterPushHooks,
      gate = AlwaysGlobal,
      enabled = MonorepoPolicySlots.enablePush.enabled,
      additionalSlots = Seq(MonorepoPolicySlots.enablePush)
    )
  )

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    LifecycleConfigCompiler.defaultSettings(phases)

  val defaults: Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]] =
    LifecycleCompiler.defaults(phases)

  def compile(
      hooks: MonorepoHookConfiguration
  ): Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]] =
    LifecycleCompiler.compile(hooks, phases)
}
