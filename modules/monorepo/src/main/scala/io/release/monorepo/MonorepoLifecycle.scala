package io.release.monorepo

import cats.effect.IO
import io.release.internal.HookStepCompilation
import io.release.internal.LifecycleCompiler
import io.release.internal.LifecycleConfigCompiler
import io.release.internal.LifecycleSlotSupport
import io.release.internal.ProcessStep
import io.release.monorepo.steps.{MonorepoPublishSteps, MonorepoReleaseSteps}
import sbt.Setting

/** Canonical monorepo lifecycle order and hook compilation. */
private[release] object MonorepoLifecycle {

  private type ProjectGate     = (MonorepoContext, ProjectReleaseInfo) => IO[Boolean]
  private type Phase           =
    LifecycleCompiler.Phase[MonorepoHookConfiguration, MonorepoContext, ProjectReleaseInfo]
  private type Slot            = LifecycleSlotSupport.Slot[MonorepoHookConfiguration]
  private type PolicySlot      = LifecycleSlotSupport.PolicySlot[MonorepoHookConfiguration]
  private type GlobalHookSlot  =
    LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoGlobalHookIO]
  private type ProjectHookSlot =
    LifecycleSlotSupport.HookSlot[MonorepoHookConfiguration, MonorepoProjectHookIO]

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
      configBindings = LifecycleSlotSupport.configBindings(slots)
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
      configBindings = LifecycleSlotSupport.configBindings(slots)
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
      configBindings = LifecycleSlotSupport.configBindings(hookSlot +: additionalSlots)
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
      configBindings = LifecycleSlotSupport.configBindings(hookSlot +: additionalSlots)
    )

  private[release] val phases: Seq[Phase] = Seq(
    singleBuiltIn(MonorepoReleaseSteps.initializeVcs),
    singleBuiltIn(MonorepoReleaseSteps.checkCleanWorkingDir),
    globalHookPhase(
      phase = "after-clean-check",
      hookSlot = MonorepoLifecycleSlots.afterCleanCheckHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.resolveReleaseOrder),
    globalHookPhase(
      phase = "before-selection",
      hookSlot = MonorepoLifecycleSlots.beforeSelectionHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.detectOrSelectProjects),
    globalHookPhase(
      phase = "after-selection",
      hookSlot = MonorepoLifecycleSlots.afterSelectionHooks,
      gate = AlwaysGlobal
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.checkSnapshotDependencies,
      MonorepoLifecycleSlots.enableSnapshotDependenciesCheck
    ),
    projectHookPhase(
      phase = "before-version-resolution",
      hookSlot = MonorepoLifecycleSlots.beforeVersionResolutionHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.inquireVersions),
    projectHookPhase(
      phase = "after-version-resolution",
      hookSlot = MonorepoLifecycleSlots.afterVersionResolutionHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.runClean, MonorepoLifecycleSlots.enableRunClean),
    perItemBuiltIn(MonorepoReleaseSteps.runTests, MonorepoLifecycleSlots.enableRunTests),
    projectHookPhase(
      phase = "before-release-version-write",
      hookSlot = MonorepoLifecycleSlots.beforeReleaseVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.setReleaseVersions),
    projectHookPhase(
      phase = "after-release-version-write",
      hookSlot = MonorepoLifecycleSlots.afterReleaseVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ),
    globalHookPhase(
      phase = "before-release-commit",
      hookSlot = MonorepoLifecycleSlots.beforeReleaseCommitHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.commitReleaseVersions),
    globalHookPhase(
      phase = "after-release-commit",
      hookSlot = MonorepoLifecycleSlots.afterReleaseCommitHooks,
      gate = AlwaysGlobal
    ),
    projectHookPhase(
      phase = "before-tag",
      hookSlot = MonorepoLifecycleSlots.beforeTagHooks,
      gate = AlwaysProject,
      enabled = MonorepoLifecycleSlots.enableTagging.enabled,
      additionalSlots = Seq(MonorepoLifecycleSlots.enableTagging)
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.tagReleasesPerProject,
      MonorepoLifecycleSlots.enableTagging
    ),
    projectHookPhase(
      phase = "after-tag",
      hookSlot = MonorepoLifecycleSlots.afterTagHooks,
      gate = AlwaysProject,
      enabled = MonorepoLifecycleSlots.enableTagging.enabled,
      additionalSlots = Seq(MonorepoLifecycleSlots.enableTagging)
    ),
    projectHookPhase(
      phase = "before-publish",
      hookSlot = MonorepoLifecycleSlots.beforePublishHooks,
      gate = PublishProject,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("before-publish")),
      enabled = MonorepoLifecycleSlots.enablePublish.enabled,
      additionalSlots = Seq(MonorepoLifecycleSlots.enablePublish)
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.publishArtifacts,
      MonorepoLifecycleSlots.enablePublish
    ),
    projectHookPhase(
      phase = "after-publish",
      hookSlot = MonorepoLifecycleSlots.afterPublishHooks,
      gate = PublishProject,
      crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      cachedGate = Some(publishCachedGate("after-publish")),
      enabled = MonorepoLifecycleSlots.enablePublish.enabled,
      additionalSlots = Seq(MonorepoLifecycleSlots.enablePublish)
    ),
    projectHookPhase(
      phase = "before-next-version-write",
      hookSlot = MonorepoLifecycleSlots.beforeNextVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ),
    perItemBuiltIn(MonorepoReleaseSteps.setNextVersions),
    projectHookPhase(
      phase = "after-next-version-write",
      hookSlot = MonorepoLifecycleSlots.afterNextVersionWriteHooks,
      gate = AlwaysProject,
      crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ),
    globalHookPhase(
      phase = "before-next-commit",
      hookSlot = MonorepoLifecycleSlots.beforeNextCommitHooks,
      gate = AlwaysGlobal
    ),
    singleBuiltIn(MonorepoReleaseSteps.commitNextVersions),
    globalHookPhase(
      phase = "after-next-commit",
      hookSlot = MonorepoLifecycleSlots.afterNextCommitHooks,
      gate = AlwaysGlobal
    ),
    globalHookPhase(
      phase = "before-push",
      hookSlot = MonorepoLifecycleSlots.beforePushHooks,
      gate = AlwaysGlobal,
      enabled = MonorepoLifecycleSlots.enablePush.enabled,
      additionalSlots = Seq(MonorepoLifecycleSlots.enablePush)
    ),
    singleBuiltIn(MonorepoReleaseSteps.pushChanges, MonorepoLifecycleSlots.enablePush),
    globalHookPhase(
      phase = "after-push",
      hookSlot = MonorepoLifecycleSlots.afterPushHooks,
      gate = AlwaysGlobal,
      enabled = MonorepoLifecycleSlots.enablePush.enabled,
      additionalSlots = Seq(MonorepoLifecycleSlots.enablePush)
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
