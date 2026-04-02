package io.release.monorepo

import cats.effect.IO
import sbt.{internal as _, *}

/** Topological sort of sbt's inter-project dependency graph. */
private[monorepo] object DependencyGraph {

  /** Compute the reverse-dependency map: for each project, the set of projects that depend on it.
    * Only considers dependencies within the given project set.
    */
  def dependedOnBy(
      projects: Seq[ProjectRef],
      state: State
  ): IO[Map[ProjectRef, Set[ProjectRef]]] = IO.blocking {
    val extracted  = Project.extract(state)
    val structure  = extracted.structure
    val projectSet = projects.toSet

    val reverseGraph = projects
      .flatMap { ref =>
        Project
          .getProject(ref, structure)
          .toSeq
          .flatMap(_.dependencies.map(_.project))
          .filter(projectSet.contains)
          .map(_ -> ref)
      }
      .groupBy(_._1)
      .map { case (k, v) => k -> v.map(_._2).toSet }

    reverseGraph
  }

  /** Compute all transitive dependents of the given root projects. */
  def transitiveDependents(
      roots: Set[ProjectRef],
      reverseGraph: Map[ProjectRef, Set[ProjectRef]]
  ): Set[ProjectRef] = {
    @annotation.tailrec
    def loop(queue: List[ProjectRef], visited: Set[ProjectRef]): Set[ProjectRef] =
      queue match {
        case Nil          => visited
        case head :: tail =>
          val dependents = reverseGraph.getOrElse(head, Set.empty) -- visited
          loop(dependents.toList ::: tail, visited ++ dependents)
      }

    loop(roots.toList, roots) -- roots
  }

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
      IO.blocking {
        val extracted  = Project.extract(state)
        val structure  = extracted.structure
        val projectSet = uniqueProjects.toSet
        val projectOrder = uniqueProjects.zipWithIndex.toMap

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

        // Build reverse adjacency map with caller order as the stable tiebreaker.
        val dependedOnBy: Map[ProjectRef, Vector[ProjectRef]] =
          uniqueProjects.foldLeft(Map.empty[ProjectRef, Vector[ProjectRef]].withDefaultValue(Vector.empty)) {
            case (acc, proj) =>
              dependsOn.getOrElse(proj, Set.empty).toVector
                .sortBy(projectOrder)
                .foldLeft(acc) { case (updated, dependency) =>
                  updated.updated(dependency, updated(dependency) :+ proj)
                }
          }

        // Kahn's algorithm — pure tail-recursive implementation
        val inDegree: Map[ProjectRef, Int] =
          uniqueProjects.map(p => p -> dependsOn.getOrElse(p, Set.empty).size).toMap

        @annotation.tailrec
        def loop(
            queue: scala.collection.immutable.Queue[ProjectRef],
            degrees: Map[ProjectRef, Int],
            acc: Vector[ProjectRef]
        ): Vector[ProjectRef] =
          if (queue.isEmpty) acc
          else {
            val (current, rest)       = queue.dequeue
            val (newQueue, newDegrees) = dependedOnBy(current).foldLeft((rest, degrees)) {
              case ((q, d), dependent) =>
                val nd = d(dependent) - 1
                if (nd == 0) (q.enqueue(dependent), d.updated(dependent, nd))
                else (q, d.updated(dependent, nd))
            }
            loop(newQueue, newDegrees, acc :+ current)
          }

        val seeds = uniqueProjects
          .filter(p => inDegree(p) == 0)
          .foldLeft(scala.collection.immutable.Queue.empty[ProjectRef])(_.enqueue(_))
        loop(seeds, inDegree, Vector.empty)
      }.flatMap { result =>
        if (result.length != uniqueProjects.length) {
          val remaining = uniqueProjects.filterNot(result.contains)
          IO.raiseError(
            new IllegalStateException(
              "Circular dependency detected among monorepo projects: " +
                remaining.map(_.project).mkString(", ")
            )
          )
        } else IO.pure(result)
      }
  }
}
