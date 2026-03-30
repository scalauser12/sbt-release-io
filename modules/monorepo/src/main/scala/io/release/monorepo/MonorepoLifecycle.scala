package io.release.monorepo

import cats.effect.IO
import io.release.PluginLikeSupport
import io.release.monorepo.MonorepoStepIO.ResourceStepFn
import io.release.monorepo.MonorepoStepIO.ResourceStepFn.Scope
import io.release.monorepo.steps.MonorepoReleaseSteps

/** Canonical monorepo lifecycle order, hook compilation, and process signatures. */
private[monorepo] object MonorepoLifecycle {

  sealed trait ProcessToken

  object ProcessToken {
    final case class BuiltIn(phaseId: String) extends ProcessToken
    final case class GlobalCustom(name: String, isSelectionBoundary: Boolean) extends ProcessToken
    final case class PerProjectCustom(name: String, enableCrossBuild: Boolean) extends ProcessToken
    final case class OpaqueReleaseBuilder(className: String) extends ProcessToken
  }

  private val AlwaysGlobal: MonorepoContext => Boolean                         = _ => true
  private val AlwaysProject: (MonorepoContext, ProjectReleaseInfo) => Boolean  =
    (_, _) => true
  private val PublishProject: (MonorepoContext, ProjectReleaseInfo) => Boolean =
    (ctx, _) => !ctx.skipPublish

  private sealed trait Phase {
    def rawSteps: Seq[MonorepoStepIO]
    def compile(hooks: MonorepoHookConfiguration): Seq[MonorepoStepIO]
  }

  private final case class BuiltInPhase(
      id: String,
      step: MonorepoStepIO,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ) extends Phase {
    override val rawSteps: Seq[MonorepoStepIO] = Seq(step)

    override def compile(hooks: MonorepoHookConfiguration): Seq[MonorepoStepIO] =
      if (enabled(hooks)) Seq(step) else Seq.empty
  }

  private final case class GlobalHookPhase(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[MonorepoGlobalHookIO],
      gate: MonorepoContext => Boolean,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ) extends Phase {
    override val rawSteps: Seq[MonorepoStepIO] = Seq.empty

    override def compile(hooks: MonorepoHookConfiguration): Seq[MonorepoStepIO] =
      if (enabled(hooks)) compileGlobalHooks(phase, resolveHooks(hooks), gate)
      else Seq.empty
  }

  private final case class ProjectHookPhase(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[MonorepoProjectHookIO],
      gate: (MonorepoContext, ProjectReleaseInfo) => Boolean,
      crossBuild: Boolean,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ) extends Phase {
    override val rawSteps: Seq[MonorepoStepIO] = Seq.empty

    override def compile(hooks: MonorepoHookConfiguration): Seq[MonorepoStepIO] =
      if (enabled(hooks)) compileProjectHooks(phase, resolveHooks(hooks), gate, crossBuild)
      else Seq.empty
  }

  private val phases: Seq[Phase] = Seq(
    BuiltInPhase("initialize-vcs", MonorepoReleaseSteps.initializeVcs),
    BuiltInPhase("check-clean-working-dir", MonorepoReleaseSteps.checkCleanWorkingDir),
    BuiltInPhase("resolve-release-order", MonorepoReleaseSteps.resolveReleaseOrder),
    GlobalHookPhase("before-selection", _.beforeSelectionHooks, AlwaysGlobal),
    BuiltInPhase("detect-or-select-projects", MonorepoReleaseSteps.detectOrSelectProjects),
    GlobalHookPhase("after-selection", _.afterSelectionHooks, AlwaysGlobal),
    BuiltInPhase(
      "check-snapshot-dependencies",
      MonorepoReleaseSteps.checkSnapshotDependencies,
      _.enableSnapshotDependenciesCheck
    ),
    ProjectHookPhase(
      "before-version-resolution",
      _.beforeVersionResolutionHooks,
      AlwaysProject,
      MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ),
    BuiltInPhase("inquire-versions", MonorepoReleaseSteps.inquireVersions),
    ProjectHookPhase(
      "after-version-resolution",
      _.afterVersionResolutionHooks,
      AlwaysProject,
      MonorepoReleaseSteps.inquireVersions.enableCrossBuild
    ),
    BuiltInPhase("run-clean", MonorepoReleaseSteps.runClean, _.enableRunClean),
    BuiltInPhase("run-tests", MonorepoReleaseSteps.runTests, _.enableRunTests),
    ProjectHookPhase(
      "before-release-version-write",
      _.beforeReleaseVersionWriteHooks,
      AlwaysProject,
      MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ),
    BuiltInPhase("set-release-version", MonorepoReleaseSteps.setReleaseVersions),
    ProjectHookPhase(
      "after-release-version-write",
      _.afterReleaseVersionWriteHooks,
      AlwaysProject,
      MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
    ),
    GlobalHookPhase("before-release-commit", _.beforeReleaseCommitHooks, AlwaysGlobal),
    BuiltInPhase("commit-release-versions", MonorepoReleaseSteps.commitReleaseVersions),
    GlobalHookPhase("after-release-commit", _.afterReleaseCommitHooks, AlwaysGlobal),
    ProjectHookPhase(
      "before-tag",
      _.beforeTagHooks,
      AlwaysProject,
      crossBuild = false,
      enabled = _.enableTagging
    ),
    BuiltInPhase("tag-releases", MonorepoReleaseSteps.tagReleasesPerProject, _.enableTagging),
    ProjectHookPhase(
      "after-tag",
      _.afterTagHooks,
      AlwaysProject,
      crossBuild = false,
      enabled = _.enableTagging
    ),
    ProjectHookPhase(
      "before-publish",
      _.beforePublishHooks,
      PublishProject,
      MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      enabled = _.enablePublish
    ),
    BuiltInPhase("publish-artifacts", MonorepoReleaseSteps.publishArtifacts, _.enablePublish),
    ProjectHookPhase(
      "after-publish",
      _.afterPublishHooks,
      PublishProject,
      MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
      enabled = _.enablePublish
    ),
    ProjectHookPhase(
      "before-next-version-write",
      _.beforeNextVersionWriteHooks,
      AlwaysProject,
      MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ),
    BuiltInPhase("set-next-version", MonorepoReleaseSteps.setNextVersions),
    ProjectHookPhase(
      "after-next-version-write",
      _.afterNextVersionWriteHooks,
      AlwaysProject,
      MonorepoReleaseSteps.setNextVersions.enableCrossBuild
    ),
    GlobalHookPhase("before-next-commit", _.beforeNextCommitHooks, AlwaysGlobal),
    BuiltInPhase("commit-next-versions", MonorepoReleaseSteps.commitNextVersions),
    GlobalHookPhase("after-next-commit", _.afterNextCommitHooks, AlwaysGlobal),
    GlobalHookPhase(
      "before-push",
      _.beforePushHooks,
      AlwaysGlobal,
      enabled = _.enablePush
    ),
    BuiltInPhase("push-changes", MonorepoReleaseSteps.pushChanges, _.enablePush),
    GlobalHookPhase(
      "after-push",
      _.afterPushHooks,
      AlwaysGlobal,
      enabled = _.enablePush
    )
  )

  private val builtInPhases: Seq[BuiltInPhase] =
    phases.collect { case phase: BuiltInPhase => phase }

  val defaults: Seq[MonorepoStepIO] =
    phases.flatMap(_.rawSteps)

  val defaultSignature: Seq[ProcessToken] =
    signature(defaults)

  def compile(hooks: MonorepoHookConfiguration): Seq[MonorepoStepIO] =
    phases.flatMap(_.compile(hooks))

  def signature(steps: Seq[MonorepoStepIO]): Seq[ProcessToken] =
    steps.map(stepToken)

  def releaseBuilderSignature[T](steps: Seq[T => MonorepoStepIO]): Seq[ProcessToken] =
    steps.map {
      case lifted: PluginLikeSupport.LiftedStepFn[?, ?] =>
        stepToken(lifted.step.asInstanceOf[MonorepoStepIO])
      case resource: ResourceStepFn[?]                  =>
        resource.scope match {
          case Scope.Global(isSelectionBoundary) =>
            ProcessToken.GlobalCustom(resource.name, isSelectionBoundary)
          case Scope.PerProject(enableCrossBuild) =>
            ProcessToken.PerProjectCustom(resource.name, enableCrossBuild)
        }
      case other                                        =>
        ProcessToken.OpaqueReleaseBuilder(other.getClass.getName)
    }

  private def stepToken(step: MonorepoStepIO): ProcessToken =
    builtInPhases
      .collectFirst {
        case phase if sameRef(step, phase.step) =>
          ProcessToken.BuiltIn(phase.id)
      }
      .getOrElse {
        step match {
          case global: MonorepoStepIO.Global     =>
            ProcessToken.GlobalCustom(global.name, global.isSelectionBoundary)
          case project: MonorepoStepIO.PerProject =>
            ProcessToken.PerProjectCustom(project.name, project.enableCrossBuild)
        }
      }

  private def sameRef(left: AnyRef, right: AnyRef): Boolean =
    left eq right

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
      gate: (MonorepoContext, ProjectReleaseInfo) => Boolean,
      crossBuild: Boolean
  ): Seq[MonorepoStepIO] =
    hooks.map { hook =>
      MonorepoStepIO.PerProject(
        name = s"$phase:${hook.name}",
        execute = (ctx, project) =>
          if (gate(ctx, project)) hook.execute(ctx, project)
          else IO.pure(ctx),
        validate = (ctx, project) =>
          if (gate(ctx, project)) hook.validate(ctx, project)
          else IO.unit,
        enableCrossBuild = crossBuild
      )
    }
}
