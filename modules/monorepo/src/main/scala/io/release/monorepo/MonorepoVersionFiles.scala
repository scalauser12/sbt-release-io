package io.release.monorepo

import sbt.*
import sbtrelease.ReleasePlugin.autoImport.releaseVersionFile

/** Shared version-file resolution for monorepo release steps and project discovery.
  *
  * In per-project mode, resolution uses `releaseIOMonorepoVersionFile(ref, state)`.
  * In global-version mode, all projects resolve to the shared root version file.
  */
private[monorepo] object MonorepoVersionFiles {

  def resolveConfiguredVersionFile(extracted: Extracted, state: State, ref: ProjectRef): File =
    extracted.get(MonorepoReleaseIO.releaseIOMonorepoVersionFile)(ref, state)

  def resolve(
      extracted: Extracted,
      state: State,
      ref: ProjectRef,
      useGlobalVersion: Boolean
  ): File =
    if (useGlobalVersion) extracted.get(releaseVersionFile)
    else resolveConfiguredVersionFile(extracted, state, ref)

  def resolve(state: State, ref: ProjectRef, useGlobalVersion: Boolean): File = {
    val extracted = Project.extract(state)
    resolve(extracted, state, ref, useGlobalVersion)
  }

  def resolve(state: State, ref: ProjectRef): File = {
    val extracted = Project.extract(state)
    resolve(
      extracted,
      state,
      ref,
      extracted.get(MonorepoReleaseIO.releaseIOMonorepoUseGlobalVersion)
    )
  }
}
