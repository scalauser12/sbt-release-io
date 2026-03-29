package io.release.monorepo

import cats.effect.IO
import sbt.Keys.baseDirectory
import sbt.{internal as _, *}

/** Resolves monorepo project metadata from the current sbt state. */
private[monorepo] object MonorepoProjectResolver {

  def resolveAll(state: State): IO[Seq[ProjectReleaseInfo]] =
    IO.blocking {
      val extracted   = Project.extract(state)
      val runtime     = MonorepoRuntime.fromExtracted(state, extracted)
      val projectRefs = extracted.get(MonorepoReleaseIO.releaseIOMonorepoProjects)

      projectRefs.map { ref =>
        val baseDir = (ref / baseDirectory).get(extracted.structure.data)
        (ref, baseDir, MonorepoVersionFiles.resolve(runtime, ref))
      }
    }.flatMap { entries =>
      val missing = entries.collect { case (ref, None, _) => ref.project }
      if (missing.nonEmpty)
        IO.raiseError(
          new IllegalStateException(
            s"Cannot resolve baseDirectory for project(s): ${missing.mkString(", ")}. " +
              "Ensure the project is correctly defined in the build."
          )
        )
      else
        IO.pure(entries.collect { case (ref, Some(base), vf) =>
          ProjectReleaseInfo(
            ref = ref,
            name = ref.project,
            baseDir = base,
            versionFile = vf
          )
        })
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

  def applyVersionOverrides(
      projects: Seq[ProjectReleaseInfo],
      plan: MonorepoReleasePlan
  ): Seq[ProjectReleaseInfo] =
    projects.map { project =>
      val releaseOverride = plan.releaseVersionOverrides.get(project.name)
      val nextOverride    = plan.nextVersionOverrides.get(project.name)

      if (releaseOverride.isEmpty && nextOverride.isEmpty) project
      else {
        val (currentRelease, currentNext) = project.versions.getOrElse(("", ""))
        project.copy(versions =
          Some((releaseOverride.getOrElse(currentRelease), nextOverride.getOrElse(currentNext)))
        )
      }
    }
}
