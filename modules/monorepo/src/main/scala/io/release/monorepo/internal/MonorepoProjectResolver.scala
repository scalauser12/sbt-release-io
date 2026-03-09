package io.release.monorepo.internal

import cats.effect.IO
import io.release.monorepo.{MonorepoReleaseIO, MonorepoRuntime, ProjectReleaseInfo}
import sbt.*
import sbt.Keys.baseDirectory

/** Resolves monorepo project metadata from the current sbt state. */
private[monorepo] object MonorepoProjectResolver {

  def resolveAll(state: State): IO[Seq[ProjectReleaseInfo]] =
    IO.blocking {
      val extracted   = Project.extract(state)
      val runtime     = MonorepoRuntime.fromExtracted(state, extracted)
      val projectRefs = extracted.get(MonorepoReleaseIO.releaseIOMonorepoProjects)

      projectRefs.map { ref =>
        val baseDir = (ref / baseDirectory).get(extracted.structure.data).getOrElse {
          throw new IllegalStateException(
            s"Cannot resolve baseDirectory for project '${ref.project}'. " +
              "Ensure the project is correctly defined in the build."
          )
        }

        ProjectReleaseInfo(
          ref = ref,
          name = ref.project,
          baseDir = baseDir,
          versionFile = _root_.io.release.monorepo.MonorepoVersionFiles.resolve(runtime, ref)
        )
      }
    }

  def mergeSnapshot(
      current: Seq[ProjectReleaseInfo],
      resolved: Seq[ProjectReleaseInfo]
  ): Seq[ProjectReleaseInfo] = {
    val currentByRef = current.map(p => p.ref -> p).toMap

    resolved.map { project =>
      currentByRef.get(project.ref) match {
        case Some(existing) =>
          project.copy(
            versions = existing.versions,
            tagName = existing.tagName,
            failed = existing.failed
          )
        case None           => project
      }
    }
  }

  def applyVersionOverrides(
      projects: Seq[ProjectReleaseInfo],
      plan: MonorepoReleasePlan,
      useGlobalVersion: Boolean
  ): Seq[ProjectReleaseInfo] =
    projects.map { project =>
      val currentVersions = project.versions.getOrElse("" -> "")
      val releaseOverride =
        if (useGlobalVersion) plan.globalReleaseVersion
        else plan.releaseVersionOverrides.get(project.name)
      val nextOverride    =
        if (useGlobalVersion) plan.globalNextVersion
        else plan.nextVersionOverrides.get(project.name)

      val release = releaseOverride.getOrElse(currentVersions._1)
      val next    = nextOverride.getOrElse(currentVersions._2)

      val updatedVersions =
        if (release.nonEmpty || next.nonEmpty) Some((release, next))
        else project.versions

      project.copy(versions = updatedVersions)
    }
}
