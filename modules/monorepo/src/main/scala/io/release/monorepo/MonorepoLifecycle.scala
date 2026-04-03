package io.release.monorepo

import cats.effect.IO
import io.release.internal.HookStepCompilation
import io.release.internal.LifecycleCompiler
import io.release.monorepo.steps.{MonorepoPublishSteps, MonorepoReleaseSteps}

/** Canonical monorepo lifecycle order and hook compilation. */
private[monorepo] object MonorepoLifecycle {

  private type ProjectGate = (MonorepoContext, ProjectReleaseInfo) => IO[Boolean]

  private val AlwaysGlobal: MonorepoContext => Boolean = _ => true
  private val AlwaysProject: ProjectGate               = (_, _) => IO.pure(true)
  private val PublishProject: ProjectGate              =
    MonorepoPublishSteps.shouldRunPublishHooks

  private def globalHookPhase(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[MonorepoGlobalHookIO],
      gate: MonorepoContext => Boolean,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): LifecycleCompiler.HookPhase[
    MonorepoHookConfiguration,
    MonorepoGlobalHookIO,
    MonorepoProcessStep
  ] =
    LifecycleCompiler.HookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      buildSteps = (phaseName, hooks) => compileGlobalHooks(phaseName, hooks, gate),
      enabled = enabled
    )

  private def projectHookPhase(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[MonorepoProjectHookIO],
      gate: ProjectGate,
      crossBuild: Boolean,
      enabled: MonorepoHookConfiguration => Boolean = _ => true,
      freezeGateDecision: Boolean = false
  ): LifecycleCompiler.HookPhase[
    MonorepoHookConfiguration,
    MonorepoProjectHookIO,
    MonorepoProcessStep
  ] =
    LifecycleCompiler.HookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      buildSteps = (phaseName, hooks) =>
        compileProjectHooks(phaseName, hooks, gate, crossBuild, freezeGateDecision),
      enabled = enabled
    )

  private val phases: Seq[LifecycleCompiler.Phase[MonorepoHookConfiguration, MonorepoProcessStep]] =
    Seq(
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.initializeVcs),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.checkCleanWorkingDir),
      globalHookPhase("after-clean-check", _.afterCleanCheckHooks, AlwaysGlobal),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.resolveReleaseOrder),
      globalHookPhase("before-selection", _.beforeSelectionHooks, AlwaysGlobal),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.detectOrSelectProjects),
      globalHookPhase("after-selection", _.afterSelectionHooks, AlwaysGlobal),
      LifecycleCompiler.BuiltInPhase(
        MonorepoReleaseSteps.checkSnapshotDependencies,
        _.enableSnapshotDependenciesCheck
      ),
      projectHookPhase(
        "before-version-resolution",
        _.beforeVersionResolutionHooks,
        AlwaysProject,
        MonorepoReleaseSteps.inquireVersions.enableCrossBuild
      ),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.inquireVersions),
      projectHookPhase(
        "after-version-resolution",
        _.afterVersionResolutionHooks,
        AlwaysProject,
        MonorepoReleaseSteps.inquireVersions.enableCrossBuild
      ),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.runClean, _.enableRunClean),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.runTests, _.enableRunTests),
      projectHookPhase(
        "before-release-version-write",
        _.beforeReleaseVersionWriteHooks,
        AlwaysProject,
        MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
      ),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.setReleaseVersions),
      projectHookPhase(
        "after-release-version-write",
        _.afterReleaseVersionWriteHooks,
        AlwaysProject,
        MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
      ),
      globalHookPhase(
        "before-release-commit",
        _.beforeReleaseCommitHooks,
        AlwaysGlobal
      ),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.commitReleaseVersions),
      globalHookPhase(
        "after-release-commit",
        _.afterReleaseCommitHooks,
        AlwaysGlobal
      ),
      projectHookPhase(
        "before-tag",
        _.beforeTagHooks,
        AlwaysProject,
        crossBuild = false,
        enabled = _.enableTagging
      ),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.tagReleasesPerProject, _.enableTagging),
      projectHookPhase(
        "after-tag",
        _.afterTagHooks,
        AlwaysProject,
        crossBuild = false,
        enabled = _.enableTagging
      ),
      projectHookPhase(
        "before-publish",
        _.beforePublishHooks,
        PublishProject,
        MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
        enabled = _.enablePublish,
        freezeGateDecision = true
      ),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.publishArtifacts, _.enablePublish),
      projectHookPhase(
        "after-publish",
        _.afterPublishHooks,
        PublishProject,
        MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
        enabled = _.enablePublish,
        freezeGateDecision = true
      ),
      projectHookPhase(
        "before-next-version-write",
        _.beforeNextVersionWriteHooks,
        AlwaysProject,
        MonorepoReleaseSteps.setNextVersions.enableCrossBuild
      ),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.setNextVersions),
      projectHookPhase(
        "after-next-version-write",
        _.afterNextVersionWriteHooks,
        AlwaysProject,
        MonorepoReleaseSteps.setNextVersions.enableCrossBuild
      ),
      globalHookPhase(
        "before-next-commit",
        _.beforeNextCommitHooks,
        AlwaysGlobal
      ),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.commitNextVersions),
      globalHookPhase(
        "after-next-commit",
        _.afterNextCommitHooks,
        AlwaysGlobal
      ),
      globalHookPhase(
        "before-push",
        _.beforePushHooks,
        AlwaysGlobal,
        enabled = _.enablePush
      ),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.pushChanges, _.enablePush),
      globalHookPhase(
        "after-push",
        _.afterPushHooks,
        AlwaysGlobal,
        enabled = _.enablePush
      )
    )

  val defaults: Seq[MonorepoProcessStep] =
    LifecycleCompiler.defaults(phases)

  def compile(hooks: MonorepoHookConfiguration): Seq[MonorepoProcessStep] =
    LifecycleCompiler.compile(hooks, phases)

  private def compileGlobalHooks(
      phase: String,
      hooks: Seq[MonorepoGlobalHookIO],
      gate: MonorepoContext => Boolean
  ): Seq[MonorepoProcessStep] = {
    val gateIo: MonorepoContext => IO[Boolean] =
      ctx => IO.pure(gate(ctx))

    HookStepCompilation.compileSingleContextHooks[
      MonorepoContext,
      MonorepoGlobalHookIO,
      MonorepoProcessStep,
      Nothing
    ](
      phase = phase,
      hooks = hooks,
      gate = gateIo
    )(
      _.name,
      _.execute,
      _.validate,
      (name, execute, validate, validateWithContext) =>
        MonorepoProcessStep.Global(
          name = name,
          execute = execute,
          validate = validate,
          validateWithContext = validateWithContext
        )
    )
  }

  private def compileProjectHooks(
      phase: String,
      hooks: Seq[MonorepoProjectHookIO],
      gate: ProjectGate,
      crossBuild: Boolean,
      freezeGateDecision: Boolean
  ): Seq[MonorepoProcessStep] =
    HookStepCompilation.compileItemHooks[
      MonorepoContext,
      ProjectReleaseInfo,
      MonorepoProjectHookIO,
      MonorepoProcessStep,
      MonorepoPublishHookGateCache.HookToken
    ](
      phase = phase,
      hooks = hooks,
      gate = gate,
      cachedGate =
        if (freezeGateDecision)
          Some(
            HookStepCompilation
              .CachedItemGate[MonorepoContext, ProjectReleaseInfo, MonorepoPublishHookGateCache.HookToken](
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
          )
        else None
    )(
      _.name,
      _.execute,
      _.validate,
      (name, execute, validate, validateWithContext) =>
        MonorepoProcessStep.PerProject(
          name = name,
          execute = execute,
          validate = validate,
          enableCrossBuild = crossBuild,
          validateWithContext = validateWithContext
        )
    )
}
