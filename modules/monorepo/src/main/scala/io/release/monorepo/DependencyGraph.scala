package io.release.monorepo

import cats.effect.IO
import sbt.{internal as _, *}

/** Topological sort of sbt's inter-project dependency graph. */
private[monorepo] object DependencyGraph {

  /** Return projects in topological order (dependencies first).
    * Only considers dependencies within the given project set.
    */
  def topologicalSort(
      projects: Seq[ProjectRef],
      state: State
  ): IO[Seq[ProjectRef]] = {
    val uniqueProjects = projects.distinct
    if (uniqueProjects.isEmpty) IO.pure(Seq.empty)
    else
      IO.defer {
        val extracted  = Project.extract(state)
        val structure  = extracted.structure
        val projectSet = uniqueProjects.toSet

        // Build adjacency map: project -> set of projects it depends on (within our set)
        val dependsOn: Map[ProjectRef, Set[ProjectRef]] = uniqueProjects.map { ref =>
          val deps = Project
            .getProject(ref, structure)
            .toSeq
            .flatMap(_.dependencies.map(_.project))
            .filter(projectSet.contains)
            .toSet
          ref -> deps
        }.toMap

        // Build reverse adjacency map: project -> set of projects that depend on it
        val dependedOnBy: Map[ProjectRef, Set[ProjectRef]] =
          dependsOn.toSeq
            .flatMap { case (proj, deps) => deps.map(_ -> proj) }
            .groupBy(_._1)
            .map { case (k, v) => k -> v.map(_._2).toSet }
            .withDefaultValue(Set.empty)

        // Kahn's algorithm — pure tail-recursive implementation
        val inDegree: Map[ProjectRef, Int] =
          uniqueProjects.map(p => p -> dependsOn.getOrElse(p, Set.empty).size).toMap

        @annotation.tailrec
        def loop(
            queue: List[ProjectRef],
            degrees: Map[ProjectRef, Int],
            acc: List[ProjectRef]
        ): List[ProjectRef] =
          queue match {
            case Nil             => acc.reverse
            case current :: rest =>
              val (newQueue, newDegrees) = dependedOnBy(current).foldLeft((rest, degrees)) {
                case ((q, d), dependent) =>
                  val nd = d(dependent) - 1
                  if (nd == 0) (dependent :: q, d.updated(dependent, nd))
                  else (q, d.updated(dependent, nd))
              }
              loop(newQueue, newDegrees, current :: acc)
          }

        val seeds  = uniqueProjects.filter(p => inDegree(p) == 0).toList
        val result = loop(seeds, inDegree, Nil)

        if (result.length != uniqueProjects.length) {
          val remaining = uniqueProjects.filterNot(result.contains)
          IO.raiseError(
            new IllegalStateException(
              s"Circular dependency detected among monorepo projects: ${remaining.map(_.project).mkString(", ")}"
            )
          )
        } else {
          IO.pure(result)
        }
      }
  }
}
