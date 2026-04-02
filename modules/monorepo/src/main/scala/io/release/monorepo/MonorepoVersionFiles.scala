package io.release.monorepo

import cats.effect.IO
import io.release.ReleaseIO
import io.release.internal.SbtRuntime
import io.release.monorepo.MonorepoReleaseIO as MR
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
    runtime.extracted.get(MR.releaseIOMonorepoVersioningFile)(ref, runtime.state)

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
      MR.releaseIOMonorepoVersioningFile         :=
        runtime.extracted.get(MR.releaseIOMonorepoVersioningFile),
      MR.releaseIOMonorepoVersioningReadVersion  := runtime.readVersion,
      MR.releaseIOMonorepoVersioningFileContents := runtime.versionFileContents
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
    sessionSettings(state).map(
      _ ++ ReleaseIO.existingReleaseManifestSettings(state, projectRefs)
    )

  private def sessionSettingsIfDefined(state: State): Seq[sbt.Setting[?]] = {
    val extracted = SbtRuntime.extracted(state)

    (
      extracted.getOpt(MR.releaseIOMonorepoVersioningFile),
      extracted.getOpt(MR.releaseIOMonorepoVersioningReadVersion),
      extracted.getOpt(MR.releaseIOMonorepoVersioningFileContents)
    ) match {
      case (Some(versionFile), Some(readVersion), Some(versionFileContents)) =>
        Seq(
          MR.releaseIOMonorepoVersioningFile         := versionFile,
          MR.releaseIOMonorepoVersioningReadVersion  := readVersion,
          MR.releaseIOMonorepoVersioningFileContents := versionFileContents
        )
      case _                                                                 => Seq.empty
    }
  }
}
