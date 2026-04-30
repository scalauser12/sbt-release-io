package io.release.monorepo.internal

import cats.effect.IO
import io.release.monorepo.*
import sbt.Keys.baseDirectory
import sbt.{internal as _, *}

/** Resolves monorepo project metadata from the current sbt state. */
private[monorepo] object MonorepoProjectResolver {

  def resolveAll(state: State): IO[Seq[ProjectReleaseInfo]] =
    IO.blocking {
      val extracted   = Project.extract(state)
      val runtime     = MonorepoRuntime.fromExtracted(state, extracted)
      val projectRefs =
        extracted.get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects)

      val baseDirs =
        projectRefs.map(ref => ref -> (ref / baseDirectory).get(extracted.structure.data))
      (runtime, baseDirs)
    }.flatMap { case (runtime, baseDirs) =>
      // Resolve baseDirectory before touching any other project-scoped key. An
      // invalid ref's `releaseIOMonorepoVersioningFile` lookup raises an
      // "undefined setting" error that would otherwise mask the friendly
      // diagnostic below.
      val missing = baseDirs.collect { case (ref, None) => ref.project }
      if (missing.nonEmpty)
        IO.raiseError(
          new IllegalStateException(
            s"Cannot resolve baseDirectory for project(s): ${missing.mkString(", ")}. " +
              "Ensure the project is correctly defined in the build."
          )
        )
      else
        IO.blocking {
          baseDirs.collect { case (ref, Some(base)) =>
            ProjectReleaseInfo(
              ref = ref,
              name = ref.project,
              baseDir = base,
              versionFile = MonorepoVersionFiles.resolve(runtime, ref)
            )
          }
        }
    }

  def resolveOrdered(state: State): IO[Seq[ProjectReleaseInfo]] =
    resolveAll(state).flatMap { projects =>
      val byRef = projects.map(p => p.ref -> p).toMap
      DependencyGraph.topologicalSort(projects.map(_.ref), state).map { orderedRefs =>
        orderedRefs.flatMap(byRef.get)
      }
    }

  def mergeSnapshot(
      current: Seq[ProjectReleaseInfo],
      resolved: Seq[ProjectReleaseInfo]
  ): Seq[ProjectReleaseInfo] = {
    val currentByRef = current.map(p => p.ref -> p).toMap

    resolved.map { project =>
      currentByRef
        .get(project.ref)
        .fold(project)(existing =>
          project.copy(
            versions = existing.versions,
            tagName = existing.tagName,
            failed = existing.failed,
            failureCause = existing.failureCause
          )
        )
    }
  }

  /** Merge CLI version overrides into the current project snapshot.
    *
    * Partial overrides are preserved as a half-resolved `(releaseVersion, nextVersion)` pair.
    * When a project has no current versions yet, the missing side is intentionally stored as `""`
    * so `inquire-versions` can resolve it later. Callers that require both values should consult
    * [[ProjectReleaseInfo.resolvedVersions]].
    */
  def applyVersionOverrides(
      projects: Seq[ProjectReleaseInfo],
      plan: MonorepoReleasePlan
  ): Seq[ProjectReleaseInfo] =
    projects.map { project =>
      val releaseOverride = plan.releaseVersionOverrides.get(project.name)
      val nextOverride    = plan.nextVersionOverrides.get(project.name)

      if (releaseOverride.isEmpty && nextOverride.isEmpty) project
      else {
        val mergedVersions = project.versions match {
          case Some((currentRelease, currentNext)) =>
            Some(
              releaseOverride.getOrElse(currentRelease) ->
                nextOverride.getOrElse(currentNext)
            )
          case None                                =>
            // Keep a partial CLI override visible to `inquire-versions` by leaving the
            // unresolved side as `""` until both versions have been resolved.
            Some(
              releaseOverride.getOrElse("") ->
                nextOverride.getOrElse("")
            )
        }
        project.copy(versions = mergedVersions)
      }
    }
}
