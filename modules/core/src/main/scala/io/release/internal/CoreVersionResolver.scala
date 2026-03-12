package io.release.internal

import cats.effect.IO
import io.release.ReleaseIO.{
  releaseIOReadVersion,
  releaseIOUseGlobalVersion,
  releaseIOVersionFile,
  releaseIOWriteVersion
}
import sbt.{internal => _, *}

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
      versionFile = SbtRuntime.getSetting(state, releaseIOVersionFile),
      readVersion = SbtRuntime.getSetting(state, releaseIOReadVersion),
      writeVersion = SbtRuntime.getSetting(state, releaseIOWriteVersion),
      useGlobalVersion = SbtRuntime.getSetting(state, releaseIOUseGlobalVersion)
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
      releaseVersionOverride = plan.flatMap(_.releaseVersionOverride),
      nextVersionOverride = plan.flatMap(_.nextVersionOverride),
      useGlobalVersion = settings.useGlobalVersion
    )
  }

  def sessionSettings(state: State): Seq[Setting[?]] = {
    val settings = resolveCurrentSettings(state)

    Seq(
      releaseIOVersionFile      := settings.versionFile,
      releaseIOReadVersion      := settings.readVersion,
      releaseIOWriteVersion     := settings.writeVersion,
      releaseIOUseGlobalVersion := settings.useGlobalVersion
    )
  }
}
