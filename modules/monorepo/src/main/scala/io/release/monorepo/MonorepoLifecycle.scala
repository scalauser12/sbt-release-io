package io.release.monorepo

import cats.effect.IO
import io.release.monorepo.steps.{MonorepoPublishSteps, MonorepoReleaseSteps}

/** Canonical monorepo lifecycle order and hook compilation. */
private[monorepo] object MonorepoLifecycle {

  private type ProjectGate = (MonorepoContext, ProjectReleaseInfo) => IO[Boolean]

  private val AlwaysGlobal: MonorepoContext => Boolean                         = _ => true
  private val AlwaysProject: ProjectGate                                       = (_, _) => IO.pure(true)
  private val PublishProject: ProjectGate                                      =
    MonorepoPublishSteps.shouldRunPublishHooks

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
      gate: ProjectGate,
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

  val defaults: Seq[MonorepoStepIO] =
    phases.flatMap(_.rawSteps)

  def compile(hooks: MonorepoHookConfiguration): Seq[MonorepoStepIO] =
    phases.flatMap(_.compile(hooks))

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
