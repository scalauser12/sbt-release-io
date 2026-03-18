package io.release.monorepo

import cats.effect.IO
import io.release.ReleaseIO.releaseIOVersionFile
import io.release.monorepo.{MonorepoReleaseIO as MR}
import sbt.{internal as _, *}

/** Shared version-file resolution for monorepo release steps and project discovery.
  *
  * In per-project mode, resolution uses `releaseIOMonorepoVersionFile(ref, state)`.
  * In global-version mode, all projects resolve to the shared root version file.
  */
private[monorepo] object MonorepoVersionFiles {

  /** Bundles all version-related inputs for a single project. */
  final case class VersionInputs(
      versionFile: File,
      readVersion: File => IO[String],
      versionFileContents: (File, String) => IO[String],
      useGlobalVersion: Boolean
  )

  // ── File resolution ──────────────────────────────────────────────────

  private def resolveConfiguredVersionFile(runtime: MonorepoRuntime, ref: ProjectRef): File =
    runtime.extracted.get(MR.releaseIOMonorepoVersionFile)(ref, runtime.state)

  def resolve(runtime: MonorepoRuntime, ref: ProjectRef): File =
    if (runtime.useGlobalVersion) runtime.extracted.get(releaseIOVersionFile)
    else resolveConfiguredVersionFile(runtime, ref)

  def resolve(state: State, ref: ProjectRef): File =
    resolve(MonorepoRuntime.fromState(state), ref)

  // ── Input resolution ─────────────────────────────────────────────────

  def resolveInputs(runtime: MonorepoRuntime, ref: ProjectRef): VersionInputs =
    VersionInputs(
      versionFile = resolve(runtime, ref),
      readVersion = runtime.readVersion,
      versionFileContents = runtime.versionFileContents,
      useGlobalVersion = runtime.useGlobalVersion
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
      MR.releaseIOMonorepoVersionFile         :=
        runtime.extracted.get(MR.releaseIOMonorepoVersionFile),
      MR.releaseIOMonorepoReadVersion         := runtime.readVersion,
      MR.releaseIOMonorepoVersionFileContents := runtime.versionFileContents,
      MR.releaseIOMonorepoUseGlobalVersion    := runtime.useGlobalVersion
    )

  def sessionSettings(state: State): IO[Seq[sbt.Setting[?]]] =
    IO.blocking {
      val runtime = MonorepoRuntime.fromState(state)
      sessionSettings(runtime)
    }
}
