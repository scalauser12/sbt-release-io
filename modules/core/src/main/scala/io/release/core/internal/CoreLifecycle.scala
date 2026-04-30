package io.release.core.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.core.internal.steps.PublishSteps
import io.release.core.internal.steps.ReleaseSteps
import io.release.runtime.HookPhases
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
      gateKey: Option[ReleaseContext => String] = None,
      enabled: CoreHookConfiguration => Boolean = _ => true,
      narrowExecute: Option[ReleaseContext => IO[Boolean]] = None
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
      executeTrackedOf = Some((hook: ReleaseHookIO) => ReleaseHookIO.trackedExecute(hook)),
      validateOf = (hook: ReleaseHookIO) => hook.validate,
      crossBuild = config.crossBuild,
      freezeGate = config.freezeGate,
      gateKey = config.gateKey,
      enabled = config.enabled,
      narrowExecute = config.narrowExecute
    )

  private val publishGate: ReleaseContext => IO[Boolean] =
    PublishSteps.shouldRunPublishHooks

  // Publish hooks freeze their validate-time gate decision so publishTo / publish / skip
  // drift between cross-build iterations cannot flip execute-time behavior. Core runs
  // against a single active release context, so the current unscoped scalaVersion is the
  // stable per-iteration key.
  private val scalaVersionKey: ReleaseContext => String =
    ctx =>
      SbtRuntime
        .extracted(ctx.state)
        .getOpt(Keys.scalaVersion)
        .getOrElse("")

  /** Narrow `after-publish` execution to iterations where `publish-artifacts` actually
    * ran. The frozen validate-time gate stays the upper bound (so `releaseIO check` still
    * rehearses the hook); at execute time we additionally require that the publish step
    * recorded its iteration key. This suppresses afterPublish when a `before-publish`
    * hook flips `ctx.skipPublish` or installs `publish/skip := true` and the publish task
    * therefore no-ops.
    */
  private val afterPublishNarrow: ReleaseContext => IO[Boolean] =
    ctx =>
      IO.pure(
        ctx.publishExecutedKeys.exists(_.contains(PublishSteps.publishGateKey(ctx)))
      )

  /** Narrow `after-push` execution to releases where `push-changes` actually pushed.
    * `enablePush` (the policy gate) only says "the push step is in the pipeline";
    * the step can still complete successfully without pushing when the operator
    * declined (`default-push-answer n`, `releaseIODefaultsPushAnswer := Some(false)`,
    * non-interactive no-default, interactive decline, EOF). Validation rehearses
    * the hook unconditionally (so `releaseIO check` exercises hook code), and
    * execution requires that the push step recorded `markPushExecuted`.
    */
  private val afterPushNarrow: ReleaseContext => IO[Boolean] =
    ctx => IO.pure(ctx.pushExecuted)

  // @formatter:off
  private val afterCleanCheck = HookPhaseConfig(
    phase = HookPhases.AfterCleanCheck,
    resolveHooks = _.afterCleanCheckHooks
  )
  private val beforeVersionResolution = HookPhaseConfig(
    phase = HookPhases.BeforeVersionResolution,
    resolveHooks = _.beforeVersionResolutionHooks
  )
  private val afterVersionResolution = HookPhaseConfig(
    phase = HookPhases.AfterVersionResolution,
    resolveHooks = _.afterVersionResolutionHooks
  )
  private val beforeReleaseVersionWrite = HookPhaseConfig(
    phase = HookPhases.BeforeReleaseVersionWrite,
    resolveHooks = _.beforeReleaseVersionWriteHooks
  )
  private val afterReleaseVersionWrite = HookPhaseConfig(
    phase = HookPhases.AfterReleaseVersionWrite,
    resolveHooks = _.afterReleaseVersionWriteHooks
  )
  private val beforeReleaseCommit = HookPhaseConfig(
    phase = HookPhases.BeforeReleaseCommit,
    resolveHooks = _.beforeReleaseCommitHooks
  )
  private val afterReleaseCommit = HookPhaseConfig(
    phase = HookPhases.AfterReleaseCommit,
    resolveHooks = _.afterReleaseCommitHooks
  )
  private val beforeTag = HookPhaseConfig(
    phase = HookPhases.BeforeTag,
    resolveHooks = _.beforeTagHooks,
    enabled = _.enableTagging
  )
  private val afterTag = HookPhaseConfig(
    phase = HookPhases.AfterTag,
    resolveHooks = _.afterTagHooks,
    enabled = _.enableTagging
  )
  private val beforePublish = HookPhaseConfig(
    phase = HookPhases.BeforePublish,
    resolveHooks = _.beforePublishHooks,
    gate = publishGate,
    crossBuild =
      ReleaseSteps.publishArtifacts.enableCrossBuild,
    freezeGate = true,
    gateKey = Some(scalaVersionKey),
    enabled = _.enablePublish
  )
  private val afterPublish = HookPhaseConfig(
    phase = HookPhases.AfterPublish,
    resolveHooks = _.afterPublishHooks,
    gate = publishGate,
    crossBuild =
      ReleaseSteps.publishArtifacts.enableCrossBuild,
    freezeGate = true,
    gateKey = Some(scalaVersionKey),
    enabled = _.enablePublish,
    narrowExecute = Some(afterPublishNarrow)
  )
  private val beforeNextVersionWrite = HookPhaseConfig(
    phase = HookPhases.BeforeNextVersionWrite,
    resolveHooks = _.beforeNextVersionWriteHooks
  )
  private val afterNextVersionWrite = HookPhaseConfig(
    phase = HookPhases.AfterNextVersionWrite,
    resolveHooks = _.afterNextVersionWriteHooks
  )
  private val beforeNextCommit = HookPhaseConfig(
    phase = HookPhases.BeforeNextCommit,
    resolveHooks = _.beforeNextCommitHooks
  )
  private val afterNextCommit = HookPhaseConfig(
    phase = HookPhases.AfterNextCommit,
    resolveHooks = _.afterNextCommitHooks
  )
  private val beforePush = HookPhaseConfig(
    phase = HookPhases.BeforePush,
    resolveHooks = _.beforePushHooks,
    enabled = _.enablePush
  )
  private val afterPush = HookPhaseConfig(
    phase = HookPhases.AfterPush,
    resolveHooks = _.afterPushHooks,
    enabled = _.enablePush,
    narrowExecute = Some(afterPushNarrow)
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
