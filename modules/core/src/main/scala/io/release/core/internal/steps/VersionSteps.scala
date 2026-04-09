package io.release.core.internal.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.core.internal.CoreStepAliases.Step
import io.release.core.internal.CoreStepFactory
import io.release.runtime.engine.BuiltInStepRole
import io.release.core.internal.VersionPlan
import io.release.runtime.engine.ProcessStep
import io.release.runtime.workflow.DefaultVersionFileIO
import sbt.State
import sbt.{internal as _, *}

/** Version-related release steps: inquire, set, commit versions. */
private[release] object VersionSteps {

  private[steps] type ResolvedSettings = ReleaseVersionWorkflow.ResolvedSettings
  private[steps] val ResolvedSettings = ReleaseVersionWorkflow.ResolvedSettings

  private[release] type ResolvedVersions = ReleaseVersionWorkflow.ResolvedVersions
  private[release] val ResolvedVersions = ReleaseVersionWorkflow.ResolvedVersions

  private[steps] def resolveCurrentSettings(state: State): ResolvedSettings =
    ReleaseVersionWorkflow.resolveCurrentSettings(state)

  private[steps] def sessionSettings(state: State): Seq[Setting[?]] =
    ReleaseVersionWorkflow.sessionSettings(state)

  private[steps] def sessionSettings(versionPlan: VersionPlan): Seq[Setting[?]] =
    ReleaseVersionWorkflow.sessionSettings(versionPlan)

  val defaultReadVersion: File => IO[String] =
    DefaultVersionFileIO.defaultReadVersion

  def defaultWriteVersion(useGlobalVersion: Boolean): (File, String) => IO[String] =
    DefaultVersionFileIO.defaultWriteVersion(useGlobalVersion)

  val inquireVersions: Step = ProcessStep.Single(
    name = "inquire-versions",
    validate = ReleaseVersionWorkflow.validateInquireVersions,
    roles = Set(BuiltInStepRole.ResolveVersions),
    execute = ReleaseVersionWorkflow.inquireVersions
  )

  val setReleaseVersion: Step =
    CoreStepFactory.io("set-release-version")(ReleaseVersionWorkflow.writeReleaseVersion)

  val setNextVersion: Step =
    CoreStepFactory.io("set-next-version")(ReleaseVersionWorkflow.writeNextVersion)

  val commitReleaseVersion: Step = ProcessStep.Single(
    name = "commit-release-version",
    validate = VcsSteps.validateCleanWorkingDir(_, logStartHash = false),
    execute = ReleaseVersionWorkflow.commitReleaseVersion
  )

  val commitNextVersion: Step = ProcessStep.Single(
    name = "commit-next-version",
    validate = VcsSteps.validateCleanWorkingDir(_, logStartHash = false),
    execute = ReleaseVersionWorkflow.commitNextVersion
  )

  private[release] def resolveVersions(
      ctx: ReleaseContext,
      allowPrompts: Boolean
  ): IO[(ReleaseContext, ResolvedVersions)] =
    ReleaseVersionWorkflow.resolveVersions(ctx, allowPrompts)

  private[release] def resolveVersionPlan(
      ctx: ReleaseContext,
      resolveSettings: State => ResolvedSettings = resolveCurrentSettings
  ): VersionPlan =
    ReleaseVersionWorkflow.resolveVersionPlan(ctx, resolveSettings)
}
