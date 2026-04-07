package io.release.monorepo

import cats.effect.IO
import sbt.{internal as _, *}

/** Frequently reused monorepo settings resolved from a single sbt state snapshot. */
private[monorepo] final case class MonorepoRuntime(
    state: State,
    extracted: Extracted,
    readVersion: File => IO[String],
    versionFileContents: (File, String) => IO[String]
)

private[monorepo] object MonorepoRuntime {

  def fromState(state: State): MonorepoRuntime =
    fromExtracted(state, Project.extract(state))

  def fromExtracted(state: State, extracted: Extracted): MonorepoRuntime =
    MonorepoRuntime(
      state = state,
      extracted = extracted,
      readVersion =
        extracted.get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningReadVersion),
      versionFileContents =
        extracted.get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFileContents)
    )
}
