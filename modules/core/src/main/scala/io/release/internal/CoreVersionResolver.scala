package io.release.internal

import cats.effect.IO
import io.release.ReleaseIO.{releaseIOReadVersion, releaseIOWriteVersion}
import io.release.ReleaseKeys
import sbt.*
import sbtrelease.ReleasePlugin.autoImport.{releaseUseGlobalVersion, releaseVersionFile}

import java.io.File

/** Resolves version-related built-in step inputs from the current sbt state. */
private[release] object CoreVersionResolver {

  final case class ResolvedSettings(
      versionFile: File,
      readVersion: File => IO[String],
      writeVersion: (File, String) => IO[String],
      useGlobalVersion: Boolean
  )

  def resolveCurrentSettings(state: State): ResolvedSettings =
    ResolvedSettings(
      versionFile = SbtRuntime.getSetting(state, releaseVersionFile),
      readVersion = SbtRuntime.getSetting(state, releaseIOReadVersion),
      writeVersion = SbtRuntime.getSetting(state, releaseIOWriteVersion),
      useGlobalVersion = SbtRuntime.getSetting(state, releaseUseGlobalVersion)
    )

  def resolve(
      state: State,
      resolveSettings: State => ResolvedSettings = resolveCurrentSettings
  ): VersionPlan = {
    val settings = resolveSettings(state)
    val plan     = CoreReleasePlanner.current(state)

    VersionPlan(
      versionFile = settings.versionFile,
      readVersion = settings.readVersion,
      writeVersion = settings.writeVersion,
      releaseVersionOverride = plan.flatMap(_.releaseVersionOverride).orElse(
        state.get(ReleaseKeys.commandLineReleaseVersion).flatten
      ),
      nextVersionOverride = plan.flatMap(_.nextVersionOverride).orElse(
        state.get(ReleaseKeys.commandLineNextVersion).flatten
      ),
      useGlobalVersion = settings.useGlobalVersion
    )
  }

  def sessionSettings(state: State): Seq[Setting[?]] = {
    val settings = resolveCurrentSettings(state)

    Seq(
      releaseVersionFile := settings.versionFile,
      releaseIOReadVersion := settings.readVersion,
      releaseIOWriteVersion := settings.writeVersion,
      releaseUseGlobalVersion := settings.useGlobalVersion
    )
  }
}
