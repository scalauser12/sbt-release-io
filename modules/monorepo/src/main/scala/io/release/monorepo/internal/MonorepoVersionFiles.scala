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

  /** Promote late-bound monorepo version-file resolver definitions from
    * `structure.settings` into `session.rawAppend` so they survive later
    * structure rebuilds.
    *
    * Hooks that install the resolver triple via `Extracted.appendWithSession`
    * place the settings only in `structure.settings`. Subsequent
    * `SbtRuntime.appendSessionSettings` calls (in version-write, commit,
    * and tag steps) rebuild the structure from `session.mergeSettings`,
    * which excludes those overlays. Lifting the triple before each such
    * call promotes the actual transient setting definitions, plus any
    * transient definitions they depend on, into `session.rawAppend`. Keeping
    * the definitions rather than their resolved values preserves `.value`
    * dependencies across future rebuilds.
    *
    * A repeated call is a true no-op: once promoted, the definitions are part
    * of `session.mergeSettings` and no longer appear in the transient suffix.
    */
  def liftLateBoundVersioningSettings(state: State): State =
    SbtRuntime.promoteTransientSettingsByKey(
      state,
      Seq(
        releaseIOMonorepoVersioningFile.key,
        releaseIOMonorepoVersioningReadVersion.key,
        releaseIOMonorepoVersioningFileContents.key
      )
    )

  /** Persist settings without dropping hook-installed late-bound version-file
    * definitions. An empty settings sequence still performs the lift, while
    * retaining the lifted state's no-op identity instead of rebuilding it.
    */
  def appendSessionSettingsPreservingVersioning(
      state: State,
      settings: Seq[Setting[?]]
  ): State = {
    val lifted = liftLateBoundVersioningSettings(state)
    if (settings.isEmpty) lifted else SbtRuntime.appendSessionSettings(lifted, settings)
  }
}
