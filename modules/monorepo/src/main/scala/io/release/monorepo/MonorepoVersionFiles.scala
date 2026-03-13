package io.release.monorepo

import io.release.ReleaseIO.releaseIOVersionFile
import sbt.{internal as _, *}

/** Shared version-file resolution for monorepo release steps and project discovery.
  *
  * In per-project mode, resolution uses `releaseIOMonorepoVersionFile(ref, state)`.
  * In global-version mode, all projects resolve to the shared root version file.
  */
private[monorepo] object MonorepoVersionFiles {

  private def resolveConfiguredVersionFile(runtime: MonorepoRuntime, ref: ProjectRef): File =
    runtime.extracted.get(MonorepoReleaseIO.releaseIOMonorepoVersionFile)(ref, runtime.state)

  def resolve(runtime: MonorepoRuntime, ref: ProjectRef): File =
    if (runtime.useGlobalVersion) runtime.extracted.get(releaseIOVersionFile)
    else resolveConfiguredVersionFile(runtime, ref)

  def resolve(state: State, ref: ProjectRef): File =
    resolve(MonorepoRuntime.fromState(state), ref)
}
