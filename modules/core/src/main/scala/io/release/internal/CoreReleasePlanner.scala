package io.release.internal

import cats.effect.IO
import io.release.ReleaseIO.{releaseIOReadVersion, releaseIOWriteVersion}
import sbt.*
import sbtrelease.ReleasePlugin.autoImport.{releaseUseGlobalVersion, releaseVersionFile}

import java.io.File

/** Builds and stores the typed execution plan for the core release command. */
private[release] object CoreReleasePlanner {

  final case class ResolvedSettings(
      versionFile: File,
      readVersion: File => IO[String],
      writeVersion: (File, String) => IO[String],
      useGlobalVersion: Boolean
  )

  final case class Inputs(
      useDefaults: Boolean,
      skipTests: Boolean,
      skipPublish: Boolean,
      interactive: Boolean,
      crossBuild: Boolean,
      releaseVersionOverride: Option[String],
      nextVersionOverride: Option[String],
      tagDefault: Option[String]
  )

  def resolve(state: State): ResolvedSettings =
    ResolvedSettings(
      versionFile = SbtRuntime.getSetting(state, releaseVersionFile),
      readVersion = SbtRuntime.getSetting(state, releaseIOReadVersion),
      writeVersion = SbtRuntime.getSetting(state, releaseIOWriteVersion),
      useGlobalVersion = SbtRuntime.getSetting(state, releaseUseGlobalVersion)
    )

  def build(state: State, inputs: Inputs): CoreReleasePlan =
    build(resolve(state), inputs)

  def build(settings: ResolvedSettings, inputs: Inputs): CoreReleasePlan = {
    val flags       = ExecutionFlags(
      useDefaults = inputs.useDefaults,
      skipTests = inputs.skipTests,
      skipPublish = inputs.skipPublish,
      interactive = inputs.interactive,
      crossBuild = inputs.crossBuild
    )
    val versionPlan = VersionPlan(
      versionFile = settings.versionFile,
      readVersion = settings.readVersion,
      writeVersion = settings.writeVersion,
      releaseVersionOverride = inputs.releaseVersionOverride,
      nextVersionOverride = inputs.nextVersionOverride,
      useGlobalVersion = settings.useGlobalVersion
    )

    CoreReleasePlan(flags = flags, version = versionPlan, tag = TagPlan(inputs.tagDefault))
  }

  def attach(state: State, plan: CoreReleasePlan): State =
    state.put(InternalKeys.coreReleasePlan, plan)

  def current(state: State): Option[CoreReleasePlan] =
    state.get(InternalKeys.coreReleasePlan)

  def require(state: State): IO[CoreReleasePlan] =
    IO.fromOption(current(state))(new IllegalStateException("Core release plan not initialized"))
}
