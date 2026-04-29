package io.release.monorepo.internal

import cats.effect.IO
import io.release.monorepo.MonorepoReleasePlugin.autoImport.*
import io.release.runtime.sbt.SbtRuntime
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

  // ── Late-bound versioning settings lift ─────────────────────────────

  /** Promote any currently-resolvable late-bound monorepo version-file
    * resolver triple from `structure.settings` into `session.rawAppend` so
    * it survives later structure rebuilds.
    *
    * Hooks that install the resolver triple via `Extracted.appendWithSession`
    * place the settings only in `structure.settings`. Subsequent
    * `SbtRuntime.appendSessionSettings` calls (in version-write, commit,
    * and tag steps) rebuild the structure from `session.mergeSettings`,
    * which excludes those overlays. Lifting the triple before each such
    * call promotes it into `session.rawAppend`, where it contributes to
    * every future `mergeSettings` and survives every later rebuild.
    *
    * No-op when any leg of the triple is undefined or when the resolver
    * already lives in `rawAppend` at the same value (idempotent: identical
    * entries appended to `rawAppend` resolve identically via last-wins).
    */
  def liftLateBoundVersioningSettings(state: State): State = {
    val extracted = SbtRuntime.extracted(state)
    val triple    = for {
      versionFile         <- extracted.getOpt(releaseIOMonorepoVersioningFile)
      readVersion         <- extracted.getOpt(releaseIOMonorepoVersioningReadVersion)
      versionFileContents <- extracted.getOpt(releaseIOMonorepoVersioningFileContents)
    } yield Seq[Setting[?]](
      releaseIOMonorepoVersioningFile         := versionFile,
      releaseIOMonorepoVersioningReadVersion  := readVersion,
      releaseIOMonorepoVersioningFileContents := versionFileContents
    )
    triple.fold(state)(SbtRuntime.appendSessionSettings(state, _))
  }
}
