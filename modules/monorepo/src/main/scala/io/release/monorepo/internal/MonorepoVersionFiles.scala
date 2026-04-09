package io.release.monorepo.internal

import cats.effect.IO
import io.release.ReleaseManifestMetadataSupport
import io.release.runtime.sbt.SbtRuntime
import io.release.monorepo.MonorepoReleasePlugin.autoImport.*
import sbt.{internal as _, *}

/** Shared version-file resolution for monorepo release steps and project discovery. */
private[monorepo] object MonorepoVersionFiles {

  /** Bundles all version-related inputs for a single project. */
  final case class VersionInputs(
      versionFile: File,
      readVersion: File => IO[String],
      versionFileContents: (File, String) => IO[String]
  )

  def resolve(runtime: MonorepoRuntime, ref: ProjectRef): File =
    runtime.extracted.get(releaseIOMonorepoVersioningFile)(ref, runtime.state)

  def resolve(state: State, ref: ProjectRef): File =
    resolve(MonorepoRuntime.fromState(state), ref)

  // ── Input resolution ─────────────────────────────────────────────────

  def resolveInputs(runtime: MonorepoRuntime, ref: ProjectRef): VersionInputs =
    VersionInputs(
      versionFile = resolve(runtime, ref),
      readVersion = runtime.readVersion,
      versionFileContents = runtime.versionFileContents
    )

  def resolveInputs(state: State, ref: ProjectRef): IO[VersionInputs] =
    IO.blocking {
      val runtime = MonorepoRuntime.fromState(state)
      resolveInputs(runtime, ref)
    }

  // ── Session settings preservation ────────────────────────────────────

  /** Settings to preserve across sbt state reloads during version writes. */
  def sessionSettings(runtime: MonorepoRuntime): Seq[sbt.Setting[?]] =
    Seq(
      releaseIOMonorepoVersioningFile         :=
        runtime.extracted.get(releaseIOMonorepoVersioningFile),
      releaseIOMonorepoVersioningReadVersion  := runtime.readVersion,
      releaseIOMonorepoVersioningFileContents := runtime.versionFileContents
    )

  def sessionSettings(state: State): IO[Seq[sbt.Setting[?]]] =
    IO.blocking(sessionSettingsIfDefined(state))

  /** Session settings that must survive later appendWithSession calls after late-bound
    * monorepo version customization has already run.
    */
  def preservedSettings(
      state: State,
      projectRefs: Seq[ProjectRef]
  ): IO[Seq[sbt.Setting[?]]] =
    IO.blocking(
      sessionSettingsIfDefined(state) ++
        ReleaseManifestMetadataSupport.existingReleaseManifestSettings(state, projectRefs)
    )

  private def sessionSettingsIfDefined(state: State): Seq[sbt.Setting[?]] = {
    val extracted = SbtRuntime.extracted(state)

    (
      extracted.getOpt(releaseIOMonorepoVersioningFile),
      extracted.getOpt(releaseIOMonorepoVersioningReadVersion),
      extracted.getOpt(releaseIOMonorepoVersioningFileContents)
    ) match {
      case (Some(versionFile), Some(readVersion), Some(versionFileContents)) =>
        Seq(
          releaseIOMonorepoVersioningFile         := versionFile,
          releaseIOMonorepoVersioningReadVersion  := readVersion,
          releaseIOMonorepoVersioningFileContents := versionFileContents
        )
      case _                                                                 => Seq.empty
    }
  }
}
