package io.release.core.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.core.internal.steps.PublishSteps
import io.release.core.internal.steps.ReleaseSteps
import io.release.runtime.engine.LifecycleCompiler
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import sbt.*

/** Canonical core lifecycle order and hook compilation. */
private[release] object CoreLifecycle {

  private case class HookPhaseConfig(
      phase: String,
      resolveHooks: CoreHookConfiguration => Seq[ReleaseHookIO],
      gate: ReleaseContext => IO[Boolean] = _ => IO.pure(true),
      crossBuild: Boolean = false,
      freezeGate: Boolean = false,
      gateKey: ReleaseContext => String = _ => "",
      enabled: CoreHookConfiguration => Boolean = _ => true
  )

  private type Phase =
    LifecycleCompiler.Phase[
      CoreHookConfiguration,
      ReleaseContext,
      Nothing
    ]

  private def builtIn(
      step: ProcessStep.Single[ReleaseContext],
      enabled: CoreHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.singleBuiltIn(step = step, enabled = enabled)

  private def hookPhase(config: HookPhaseConfig): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = config.phase,
      resolveHooks = config.resolveHooks,
      gate = config.gate,
      nameOf = (hook: ReleaseHookIO) => hook.name,
      executeOf = (hook: ReleaseHookIO) => hook.execute,
      validateOf = (hook: ReleaseHookIO) => hook.validate,
      crossBuild = config.crossBuild,
      freezeGate = config.freezeGate,
      gateKey = config.gateKey,
      enabled = config.enabled
    )

  private val publishGate: ReleaseContext => IO[Boolean] =
    PublishSteps.shouldRunPublishHooks

  private val scalaVersionKey: ReleaseContext => String =
    ctx =>
      SbtRuntime
        .extracted(ctx.state)
        .getOpt(Keys.scalaVersion)
        .getOrElse("")

  // @formatter:off
  private val afterCleanCheck = HookPhaseConfig(
    phase = "after-clean-check",
    resolveHooks = _.afterCleanCheckHooks
  )
  private val beforeVersionResolution = HookPhaseConfig(
    phase = "before-version-resolution",
    resolveHooks = _.beforeVersionResolutionHooks
  )
  private val afterVersionResolution = HookPhaseConfig(
    phase = "after-version-resolution",
    resolveHooks = _.afterVersionResolutionHooks
  )
  private val beforeReleaseVersionWrite = HookPhaseConfig(
    phase = "before-release-version-write",
    resolveHooks = _.beforeReleaseVersionWriteHooks
  )
  private val afterReleaseVersionWrite = HookPhaseConfig(
    phase = "after-release-version-write",
    resolveHooks = _.afterReleaseVersionWriteHooks
  )
  private val beforeReleaseCommit = HookPhaseConfig(
    phase = "before-release-commit",
    resolveHooks = _.beforeReleaseCommitHooks
  )
  private val afterReleaseCommit = HookPhaseConfig(
    phase = "after-release-commit",
    resolveHooks = _.afterReleaseCommitHooks
  )
  private val beforeTag = HookPhaseConfig(
    phase = "before-tag",
    resolveHooks = _.beforeTagHooks,
    enabled = _.enableTagging
  )
  private val afterTag = HookPhaseConfig(
    phase = "after-tag",
    resolveHooks = _.afterTagHooks,
    enabled = _.enableTagging
  )
  private val beforePublish = HookPhaseConfig(
    phase = "before-publish",
    resolveHooks = _.beforePublishHooks,
    gate = publishGate,
    crossBuild =
      ReleaseSteps.publishArtifacts.enableCrossBuild,
    freezeGate = true,
    gateKey = scalaVersionKey,
    enabled = _.enablePublish
  )
  private val afterPublish = HookPhaseConfig(
    phase = "after-publish",
    resolveHooks = _.afterPublishHooks,
    gate = publishGate,
    crossBuild =
      ReleaseSteps.publishArtifacts.enableCrossBuild,
    freezeGate = true,
    gateKey = scalaVersionKey,
    enabled = _.enablePublish
  )
  private val beforeNextVersionWrite = HookPhaseConfig(
    phase = "before-next-version-write",
    resolveHooks = _.beforeNextVersionWriteHooks
  )
  private val afterNextVersionWrite = HookPhaseConfig(
    phase = "after-next-version-write",
    resolveHooks = _.afterNextVersionWriteHooks
  )
  private val beforeNextCommit = HookPhaseConfig(
    phase = "before-next-commit",
    resolveHooks = _.beforeNextCommitHooks
  )
  private val afterNextCommit = HookPhaseConfig(
    phase = "after-next-commit",
    resolveHooks = _.afterNextCommitHooks
  )
  private val beforePush = HookPhaseConfig(
    phase = "before-push",
    resolveHooks = _.beforePushHooks,
    enabled = _.enablePush
  )
  private val afterPush = HookPhaseConfig(
    phase = "after-push",
    resolveHooks = _.afterPushHooks,
    enabled = _.enablePush
  )
  // @formatter:on

  private[release] lazy val phases: Seq[Phase] = Seq(
    builtIn(ReleaseSteps.initializeVcs),
    builtIn(ReleaseSteps.checkCleanWorkingDir),
    hookPhase(afterCleanCheck),
    builtIn(
      ReleaseSteps.checkSnapshotDependencies,
      _.enableSnapshotDependenciesCheck
    ),
    hookPhase(beforeVersionResolution),
    builtIn(ReleaseSteps.inquireVersions),
    hookPhase(afterVersionResolution),
    builtIn(ReleaseSteps.runClean, _.enableRunClean),
    builtIn(ReleaseSteps.runTests, _.enableRunTests),
    hookPhase(beforeReleaseVersionWrite),
    builtIn(ReleaseSteps.setReleaseVersion),
    hookPhase(afterReleaseVersionWrite),
    hookPhase(beforeReleaseCommit),
    builtIn(ReleaseSteps.commitReleaseVersion),
    hookPhase(afterReleaseCommit),
    hookPhase(beforeTag),
    builtIn(
      ReleaseSteps.tagRelease,
      _.enableTagging
    ),
    hookPhase(afterTag),
    hookPhase(beforePublish),
    builtIn(
      ReleaseSteps.publishArtifacts,
      _.enablePublish
    ),
    hookPhase(afterPublish),
    hookPhase(beforeNextVersionWrite),
    builtIn(ReleaseSteps.setNextVersion),
    hookPhase(afterNextVersionWrite),
    hookPhase(beforeNextCommit),
    builtIn(ReleaseSteps.commitNextVersion),
    hookPhase(afterNextCommit),
    hookPhase(beforePush),
    builtIn(
      ReleaseSteps.pushChanges,
      _.enablePush
    ),
    hookPhase(afterPush)
  )

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    CoreHookConfiguration.defaultSettings

  val defaults: Seq[ProcessStep.Single[ReleaseContext]] =
    LifecycleCompiler.defaultsSingle(phases)

  def compile(
      hooks: CoreHookConfiguration
  ): IO[Seq[ProcessStep.Single[ReleaseContext]]] =
    LifecycleCompiler.compileSingle(hooks, phases)
}
