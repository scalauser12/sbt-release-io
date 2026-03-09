package io.release.monorepo

import sbt.*
import sbtrelease.ReleasePlugin.autoImport.releaseVersionFile

/** Shared version-file resolution for monorepo release steps and project discovery.
  *
  * In per-project mode, the default resolver honors each project's scoped
  * sbt-release `releaseVersionFile` setting. In global-version mode, all
  * projects resolve to the shared root version file.
  */
private[monorepo] object MonorepoVersionFiles {

  private[monorepo] sealed trait DefaultResolver extends (ProjectRef => File)

  private[monorepo] final class DefaultProjectVersionFileResolver(
      projectBases: Map[ProjectRef, File],
      versionFileName: String
  ) extends DefaultResolver {
    override def apply(ref: ProjectRef): File =
      projectBases.getOrElse(
        ref,
        throw new IllegalStateException(
          s"Cannot resolve baseDirectory for project '${ref.project}' in build ${ref.build}. " +
            "Ensure the project is listed in releaseIOMonorepoProjects."
        )
      ) / versionFileName
  }

  def resolveConfiguredVersionFile(extracted: Extracted, ref: ProjectRef): File =
    extracted.get(MonorepoReleaseIO.releaseIOMonorepoVersionFile) match {
      case _: DefaultResolver => extracted.get(ref / releaseVersionFile)
      case resolver           => resolver(ref)
    }

  def resolve(extracted: Extracted, ref: ProjectRef, useGlobalVersion: Boolean): File =
    if (useGlobalVersion) extracted.get(releaseVersionFile)
    else resolveConfiguredVersionFile(extracted, ref)

  def resolve(extracted: Extracted, ref: ProjectRef): File =
    resolve(
      extracted,
      ref,
      extracted.get(MonorepoReleaseIO.releaseIOMonorepoUseGlobalVersion)
    )
}
