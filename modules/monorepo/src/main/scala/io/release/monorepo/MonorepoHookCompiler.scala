package io.release.monorepo

import cats.effect.IO
import io.release.internal.SbtRuntime
import io.release.monorepo.steps.MonorepoReleaseSteps
import sbt.State

/** Compiles semantic monorepo hook settings into the existing monorepo engine. */
private[monorepo] object MonorepoHookCompiler {

  private val AlwaysGlobal: MonorepoContext => Boolean                         = _ => true
  private val AlwaysProject: (MonorepoContext, ProjectReleaseInfo) => Boolean  =
    (_, _) => true
  private val PublishProject: (MonorepoContext, ProjectReleaseInfo) => Boolean =
    (ctx, _) => !ctx.skipPublish

  def resolve(state: State): MonorepoHookConfiguration = {
    val extracted = SbtRuntime.extracted(state)

    MonorepoHookConfiguration(
      enableSnapshotDependenciesCheck =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoEnableSnapshotDependenciesCheck),
      enableRunClean = extracted.get(MonorepoReleaseIO.releaseIOMonorepoEnableRunClean),
      enableRunTests = extracted.get(MonorepoReleaseIO.releaseIOMonorepoEnableRunTests),
      enableTagging = extracted.get(MonorepoReleaseIO.releaseIOMonorepoEnableTagging),
      enablePublish = extracted.get(MonorepoReleaseIO.releaseIOMonorepoEnablePublish),
      enablePush = extracted.get(MonorepoReleaseIO.releaseIOMonorepoEnablePush),
      beforeSelectionHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks),
      afterSelectionHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterSelectionHooks),
      beforeVersionResolutionHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeVersionResolutionHooks),
      afterVersionResolutionHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterVersionResolutionHooks),
      beforeReleaseVersionWriteHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeReleaseVersionWriteHooks),
      afterReleaseVersionWriteHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterReleaseVersionWriteHooks),
      beforeReleaseCommitHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeReleaseCommitHooks),
      afterReleaseCommitHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterReleaseCommitHooks),
      beforeTagHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeTagHooks),
      afterTagHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterTagHooks),
      beforePublishHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforePublishHooks),
      afterPublishHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterPublishHooks),
      beforeNextVersionWriteHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeNextVersionWriteHooks),
      afterNextVersionWriteHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterNextVersionWriteHooks),
      beforeNextCommitHooks =
        extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforeNextCommitHooks),
      afterNextCommitHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterNextCommitHooks),
      beforePushHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoBeforePushHooks),
      afterPushHooks = extracted.get(MonorepoReleaseIO.releaseIOMonorepoAfterPushHooks)
    )
  }

  def compile(state: State): Seq[MonorepoStepIO] =
    compile(resolve(state))

  // scalafmt: { maxColumn = 120 }
  def compile(hooks: MonorepoHookConfiguration): Seq[MonorepoStepIO] =
    // ── Setup ────────────────────────────────────────────────────────
    Seq(
      MonorepoReleaseSteps.initializeVcs,
      MonorepoReleaseSteps.checkCleanWorkingDir,
      MonorepoReleaseSteps.resolveReleaseOrder
    ) ++
      // ── Selection ──────────────────────────────────────────────────
      compileGlobalHooks("before-selection", hooks.beforeSelectionHooks, AlwaysGlobal) ++
      Seq(MonorepoReleaseSteps.detectOrSelectProjects) ++
      compileGlobalHooks("after-selection", hooks.afterSelectionHooks, AlwaysGlobal) ++
      // ── Snapshot dependencies ──────────────────────────────────────
      optionalStep(hooks.enableSnapshotDependenciesCheck)(MonorepoReleaseSteps.checkSnapshotDependencies) ++
      // ── Version resolution ─────────────────────────────────────────
      compileProjectHooks(
        "before-version-resolution",
        hooks.beforeVersionResolutionHooks,
        AlwaysProject,
        MonorepoReleaseSteps.inquireVersions.enableCrossBuild
      ) ++
      Seq(MonorepoReleaseSteps.inquireVersions) ++
      compileProjectHooks(
        "after-version-resolution",
        hooks.afterVersionResolutionHooks,
        AlwaysProject,
        MonorepoReleaseSteps.inquireVersions.enableCrossBuild
      ) ++
      // ── Clean & test ───────────────────────────────────────────────
      optionalStep(hooks.enableRunClean)(MonorepoReleaseSteps.runClean) ++
      optionalStep(hooks.enableRunTests)(MonorepoReleaseSteps.runTests) ++
      // ── Release version write ──────────────────────────────────────
      compileProjectHooks(
        "before-release-version-write",
        hooks.beforeReleaseVersionWriteHooks,
        AlwaysProject,
        MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
      ) ++
      Seq(MonorepoReleaseSteps.setReleaseVersions) ++
      compileProjectHooks(
        "after-release-version-write",
        hooks.afterReleaseVersionWriteHooks,
        AlwaysProject,
        MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
      ) ++
      // ── Release commit ─────────────────────────────────────────────
      compileGlobalHooks("before-release-commit", hooks.beforeReleaseCommitHooks, AlwaysGlobal) ++
      Seq(MonorepoReleaseSteps.commitReleaseVersions) ++
      compileGlobalHooks("after-release-commit", hooks.afterReleaseCommitHooks, AlwaysGlobal) ++
      // ── Tagging ────────────────────────────────────────────────────
      optionalSteps(hooks.enableTagging) {
        compileProjectHooks("before-tag", hooks.beforeTagHooks, AlwaysProject, crossBuild = false) ++
          Seq(MonorepoReleaseSteps.tagReleasesGlobalFacade) ++
          compileProjectHooks("after-tag", hooks.afterTagHooks, AlwaysProject, crossBuild = false)
      } ++
      // ── Publish ────────────────────────────────────────────────────
      optionalSteps(hooks.enablePublish) {
        compileProjectHooks(
          "before-publish",
          hooks.beforePublishHooks,
          PublishProject,
          MonorepoReleaseSteps.publishArtifacts.enableCrossBuild
        ) ++
          Seq(MonorepoReleaseSteps.publishArtifacts) ++
          compileProjectHooks(
            "after-publish",
            hooks.afterPublishHooks,
            PublishProject,
            MonorepoReleaseSteps.publishArtifacts.enableCrossBuild
          )
      } ++
      // ── Next version write ─────────────────────────────────────────
      compileProjectHooks(
        "before-next-version-write",
        hooks.beforeNextVersionWriteHooks,
        AlwaysProject,
        MonorepoReleaseSteps.setNextVersions.enableCrossBuild
      ) ++
      Seq(MonorepoReleaseSteps.setNextVersions) ++
      compileProjectHooks(
        "after-next-version-write",
        hooks.afterNextVersionWriteHooks,
        AlwaysProject,
        MonorepoReleaseSteps.setNextVersions.enableCrossBuild
      ) ++
      // ── Next commit ────────────────────────────────────────────────
      compileGlobalHooks("before-next-commit", hooks.beforeNextCommitHooks, AlwaysGlobal) ++
      Seq(MonorepoReleaseSteps.commitNextVersions) ++
      compileGlobalHooks("after-next-commit", hooks.afterNextCommitHooks, AlwaysGlobal) ++
      // ── Push ───────────────────────────────────────────────────────
      optionalSteps(hooks.enablePush) {
        compileGlobalHooks("before-push", hooks.beforePushHooks, AlwaysGlobal) ++
          Seq(MonorepoReleaseSteps.pushChanges) ++
          compileGlobalHooks("after-push", hooks.afterPushHooks, AlwaysGlobal)
      }
  // scalafmt: { maxColumn = 100 }

  private def optionalStep(enabled: Boolean)(step: => MonorepoStepIO): Seq[MonorepoStepIO] =
    if (enabled) Seq(step) else Seq.empty

  private def optionalSteps(enabled: Boolean)(steps: => Seq[MonorepoStepIO]): Seq[MonorepoStepIO] =
    if (enabled) steps else Seq.empty

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
