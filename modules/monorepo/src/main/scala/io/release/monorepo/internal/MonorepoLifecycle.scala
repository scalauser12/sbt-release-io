package io.release.monorepo.internal

import cats.effect.IO
import io.release.monorepo.*
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.MonorepoStepAliases.GlobalStep
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.steps.MonorepoPublishSteps
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.runtime.engine.LifecycleCompiler
import io.release.runtime.sbt.SbtRuntime
import sbt.*

/** Canonical monorepo lifecycle order and hook compilation. */
private[release] object MonorepoLifecycle {

  private val DefaultProjectHookGateKey: (MonorepoContext, ProjectReleaseInfo) => String =
    (_, _) => ""

  private case class GlobalHookPhaseConfig(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[
        MonorepoGlobalHookIO
      ],
      gate: MonorepoContext => IO[Boolean] = _ => IO.pure(true),
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  )

  private case class ProjectHookPhaseConfig(
      phase: String,
      resolveHooks: MonorepoHookConfiguration => Seq[
        MonorepoProjectHookIO
      ],
      gate: (MonorepoContext, ProjectReleaseInfo) => IO[
        Boolean
      ] = (_, _) => IO.pure(true),
      crossBuild: Boolean = false,
      freezeGate: Boolean = false,
      gateKey: (MonorepoContext, ProjectReleaseInfo) => String = DefaultProjectHookGateKey,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  )

  private type Phase =
    LifecycleCompiler.Phase[
      MonorepoHookConfiguration,
      MonorepoContext,
      ProjectReleaseInfo
    ]

  private def singleBuiltIn(
      step: GlobalStep,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.singleBuiltIn(
      step = step,
      enabled = enabled
    )

  private def perItemBuiltIn(
      step: ProjectStep,
      enabled: MonorepoHookConfiguration => Boolean = _ => true
  ): Phase =
    LifecycleCompiler.perItemBuiltIn(
      step = step,
      enabled = enabled
    )

  private def globalHookPhase(
      config: GlobalHookPhaseConfig
  ): Phase =
    LifecycleCompiler.singleHookPhase(
      phase = config.phase,
      resolveHooks = config.resolveHooks,
      gate = config.gate,
      nameOf = (hook: MonorepoGlobalHookIO) => hook.name,
      executeOf = (hook: MonorepoGlobalHookIO) => hook.execute,
      validateOf = (hook: MonorepoGlobalHookIO) => hook.validate,
      enabled = config.enabled
    )

  private def projectHookPhase(
      config: ProjectHookPhaseConfig
  ): Phase = {
    require(
      !config.freezeGate || (config.gateKey ne DefaultProjectHookGateKey),
      s"phase '${config.phase}' requires an explicit stable gateKey when freezeGate = true"
    )
    LifecycleCompiler.perItemHookPhase(
      phase = config.phase,
      resolveHooks = config.resolveHooks,
      gate = config.gate,
      nameOf = (hook: MonorepoProjectHookIO) => hook.name,
      executeOf = (hook: MonorepoProjectHookIO) => hook.execute,
      validateOf = (hook: MonorepoProjectHookIO) => hook.validate,
      crossBuild = config.crossBuild,
      freezeGate = config.freezeGate,
      gateKey = config.gateKey,
      enabled = config.enabled
    )
  }

  /** Cache key combining stable project identity and current Scala
    * version so cross-build iterations each freeze independently.
    */
  private val publishGateKey: (MonorepoContext, ProjectReleaseInfo) => String =
    (ctx, project) => {
      val sv = SbtRuntime
        .extracted(ctx.state)
        .getOpt(Keys.scalaVersion)
        .getOrElse("")
      s"${project.ref.project}:$sv"
    }

  private val publishGate: (MonorepoContext, ProjectReleaseInfo) => IO[
    Boolean
  ] =
    MonorepoPublishSteps.shouldRunPublishHooks

  // @formatter:off
  private val afterCleanCheck = GlobalHookPhaseConfig(
    phase = "after-clean-check",
    resolveHooks = _.afterCleanCheckHooks
  )
  private val beforeSelection = GlobalHookPhaseConfig(
    phase = "before-selection",
    resolveHooks = _.beforeSelectionHooks
  )
  private val afterSelection = GlobalHookPhaseConfig(
    phase = "after-selection",
    resolveHooks = _.afterSelectionHooks
  )
  private val beforeVersionResolution = ProjectHookPhaseConfig(
    phase = "before-version-resolution",
    resolveHooks = _.beforeVersionResolutionHooks,
    crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
  )
  private val afterVersionResolution = ProjectHookPhaseConfig(
    phase = "after-version-resolution",
    resolveHooks = _.afterVersionResolutionHooks,
    crossBuild = MonorepoReleaseSteps.inquireVersions.enableCrossBuild
  )
  private val beforeReleaseVersionWrite = ProjectHookPhaseConfig(
    phase = "before-release-version-write",
    resolveHooks = _.beforeReleaseVersionWriteHooks,
    crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
  )
  private val afterReleaseVersionWrite = ProjectHookPhaseConfig(
    phase = "after-release-version-write",
    resolveHooks = _.afterReleaseVersionWriteHooks,
    crossBuild = MonorepoReleaseSteps.setReleaseVersions.enableCrossBuild
  )
  private val beforeReleaseCommit = GlobalHookPhaseConfig(
    phase = "before-release-commit",
    resolveHooks = _.beforeReleaseCommitHooks
  )
  private val afterReleaseCommit = GlobalHookPhaseConfig(
    phase = "after-release-commit",
    resolveHooks = _.afterReleaseCommitHooks
  )
  private val beforeTag = ProjectHookPhaseConfig(
    phase = "before-tag",
    resolveHooks = _.beforeTagHooks,
    enabled = _.enableTagging
  )
  private val afterTag = ProjectHookPhaseConfig(
    phase = "after-tag",
    resolveHooks = _.afterTagHooks,
    enabled = _.enableTagging
  )
  private val beforePublish = ProjectHookPhaseConfig(
    phase = "before-publish",
    resolveHooks = _.beforePublishHooks,
    gate = publishGate,
    crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
    freezeGate = true,
    gateKey = publishGateKey,
    enabled = _.enablePublish
  )
  private val afterPublish = ProjectHookPhaseConfig(
    phase = "after-publish",
    resolveHooks = _.afterPublishHooks,
    gate = publishGate,
    crossBuild = MonorepoReleaseSteps.publishArtifacts.enableCrossBuild,
    freezeGate = true,
    gateKey = publishGateKey,
    enabled = _.enablePublish
  )
  private val beforeNextVersionWrite = ProjectHookPhaseConfig(
    phase = "before-next-version-write",
    resolveHooks = _.beforeNextVersionWriteHooks,
    crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
  )
  private val afterNextVersionWrite = ProjectHookPhaseConfig(
    phase = "after-next-version-write",
    resolveHooks = _.afterNextVersionWriteHooks,
    crossBuild = MonorepoReleaseSteps.setNextVersions.enableCrossBuild
  )
  private val beforeNextCommit = GlobalHookPhaseConfig(
    phase = "before-next-commit",
    resolveHooks = _.beforeNextCommitHooks
  )
  private val afterNextCommit = GlobalHookPhaseConfig(
    phase = "after-next-commit",
    resolveHooks = _.afterNextCommitHooks
  )
  private val beforePush = GlobalHookPhaseConfig(
    phase = "before-push",
    resolveHooks = _.beforePushHooks,
    enabled = _.enablePush
  )
  private val afterPush = GlobalHookPhaseConfig(
    phase = "after-push",
    resolveHooks = _.afterPushHooks,
    enabled = _.enablePush
  )
  // @formatter:on

  private[release] lazy val phases: Seq[Phase] = Seq(
    singleBuiltIn(MonorepoReleaseSteps.initializeVcs),
    singleBuiltIn(
      MonorepoReleaseSteps.checkCleanWorkingDir
    ),
    globalHookPhase(afterCleanCheck),
    singleBuiltIn(
      MonorepoReleaseSteps.resolveReleaseOrder
    ),
    globalHookPhase(beforeSelection),
    singleBuiltIn(
      MonorepoReleaseSteps.detectOrSelectProjects
    ),
    globalHookPhase(afterSelection),
    perItemBuiltIn(
      MonorepoReleaseSteps.checkSnapshotDependencies,
      _.enableSnapshotDependenciesCheck
    ),
    projectHookPhase(beforeVersionResolution),
    perItemBuiltIn(
      MonorepoReleaseSteps.inquireVersions
    ),
    projectHookPhase(afterVersionResolution),
    perItemBuiltIn(
      MonorepoReleaseSteps.runClean,
      _.enableRunClean
    ),
    perItemBuiltIn(
      MonorepoReleaseSteps.runTests,
      _.enableRunTests
    ),
    projectHookPhase(beforeReleaseVersionWrite),
    perItemBuiltIn(
      MonorepoReleaseSteps.setReleaseVersions
    ),
    projectHookPhase(afterReleaseVersionWrite),
    globalHookPhase(beforeReleaseCommit),
    singleBuiltIn(
      MonorepoReleaseSteps.commitReleaseVersions
    ),
    globalHookPhase(afterReleaseCommit),
    projectHookPhase(beforeTag),
    perItemBuiltIn(
      MonorepoReleaseSteps.tagReleasesPerProject,
      _.enableTagging
    ),
    projectHookPhase(afterTag),
    projectHookPhase(beforePublish),
    perItemBuiltIn(
      MonorepoReleaseSteps.publishArtifacts,
      _.enablePublish
    ),
    projectHookPhase(afterPublish),
    projectHookPhase(beforeNextVersionWrite),
    perItemBuiltIn(
      MonorepoReleaseSteps.setNextVersions
    ),
    projectHookPhase(afterNextVersionWrite),
    globalHookPhase(beforeNextCommit),
    singleBuiltIn(
      MonorepoReleaseSteps.commitNextVersions
    ),
    globalHookPhase(afterNextCommit),
    globalHookPhase(beforePush),
    singleBuiltIn(
      MonorepoReleaseSteps.pushChanges,
      _.enablePush
    ),
    globalHookPhase(afterPush)
  )

  private[release] lazy val configDefaultSettings: Seq[Setting[?]] =
    MonorepoHookConfiguration.defaultSettings

  val defaults: Seq[AnyStep] =
    LifecycleCompiler.defaults(phases)

  def compile(
      hooks: MonorepoHookConfiguration
  ): IO[Seq[AnyStep]] =
    LifecycleCompiler.compile(hooks, phases)
}
