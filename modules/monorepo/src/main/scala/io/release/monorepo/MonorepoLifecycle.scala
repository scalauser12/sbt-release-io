package io.release.monorepo

import cats.effect.IO
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
    MonorepoStepIO
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
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): LifecycleCompiler.HookPhase[
    MonorepoHookConfiguration,
    MonorepoProjectHookIO,
    MonorepoStepIO
  ] =
    LifecycleCompiler.HookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      buildSteps = (phaseName, hooks) => compileProjectHooks(phaseName, hooks, gate, crossBuild),
      enabled = enabled
    )

  private val phases: Seq[LifecycleCompiler.Phase[MonorepoHookConfiguration, MonorepoStepIO]] =
    Seq(
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.initializeVcs),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.checkCleanWorkingDir),
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
        enabled = _.enablePublish
      ),
      LifecycleCompiler.BuiltInPhase(MonorepoReleaseSteps.publishArtifacts, _.enablePublish),
      projectHookPhase(
        "after-publish",
        _.afterPublishHooks,
        PublishProject,
        MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
        enabled = _.enablePublish
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

  val defaults: Seq[MonorepoStepIO] =
    LifecycleCompiler.defaults(phases)

  def compile(hooks: MonorepoHookConfiguration): Seq[MonorepoStepIO] =
    LifecycleCompiler.compile(hooks, phases)

  private def compileGlobalHooks(
      phase: String,
      hooks: Seq[MonorepoGlobalHookIO],
      gate: MonorepoContext => Boolean
  ): Seq[MonorepoStepIO] =
    hooks.map { hook =>
      MonorepoStepIO.Global(
        name = s"$phase:${hook.name}",
        execute = ctx => if (gate(ctx)) hook.execute(ctx) else IO.pure(ctx),
        validate = ctx => if (gate(ctx)) hook.validate(ctx) else IO.unit
      )
    }

  private def compileProjectHooks(
      phase: String,
      hooks: Seq[MonorepoProjectHookIO],
      gate: ProjectGate,
      crossBuild: Boolean
  ): Seq[MonorepoStepIO] =
    hooks.map { hook =>
      MonorepoStepIO.PerProject(
        name = s"$phase:${hook.name}",
        execute = (ctx, project) =>
          gate(ctx, project).flatMap {
            case true  => hook.execute(ctx, project)
            case false => IO.pure(ctx)
          },
        validate = (ctx, project) =>
          gate(ctx, project).flatMap {
            case true  => hook.validate(ctx, project)
            case false => IO.unit
          },
        enableCrossBuild = crossBuild
      )
    }
}
