package io.release.monorepo.internal

import cats.effect.IO
import io.release.monorepo.{DependencyGraph, ProjectReleaseInfo}
import sbt.State

/** Resolves the current ordered monorepo project set from the sbt state. */
private[monorepo] object MonorepoOrderResolver {

  def resolve(state: State): IO[Seq[ProjectReleaseInfo]] =
    MonorepoProjectResolver.resolveAll(state).flatMap { projects =>
      DependencyGraph.topologicalSort(projects.map(_.ref), state).map { orderedRefs =>
        orderedRefs.flatMap(ref => projects.find(_.ref == ref))
      }
    }
}
