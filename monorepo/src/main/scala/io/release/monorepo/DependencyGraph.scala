package io.release.monorepo

import cats.effect.IO
import sbt.*

/** Topological sort of sbt's inter-project dependency graph. */
object DependencyGraph {

  /** Return projects in topological order (dependencies first).
    * Only considers dependencies within the given project set.
    */
  def topologicalSort(
      projects: Seq[ProjectRef],
      state: State
  ): IO[Seq[ProjectRef]] = IO.defer {
    val extracted  = Project.extract(state)
    val structure  = extracted.structure
    val projectSet = projects.toSet

    // Build adjacency map: project -> set of projects it depends on (within our set)
    val dependsOn: Map[ProjectRef, Set[ProjectRef]] = projects.map { ref =>
      val deps = Project
        .getProject(ref, structure)
        .toSeq
        .flatMap(_.dependencies.map(_.project))
        .filter(projectSet.contains)
        .toSet
      ref -> deps
    }.toMap

    // Build reverse adjacency map: project -> set of projects that depend on it
    val dependedOnBy: Map[ProjectRef, Set[ProjectRef]] = {
      val m = scala.collection.mutable.Map[ProjectRef, Set[ProjectRef]]()
      for {
        (proj, deps) <- dependsOn
        dep          <- deps
      } m(dep) = m.getOrElse(dep, Set.empty) + proj
      m.toMap.withDefaultValue(Set.empty)
    }

    // Kahn's algorithm
    val inDegree = scala.collection.mutable.Map[ProjectRef, Int]()
    projects.foreach(p => inDegree(p) = dependsOn.getOrElse(p, Set.empty).size)

    val queue  = scala.collection.mutable.Queue[ProjectRef]()
    val result = scala.collection.mutable.ListBuffer[ProjectRef]()

    // Start with projects that have no dependencies within the set
    projects.filter(p => inDegree(p) == 0).foreach(queue.enqueue(_))

    while (queue.nonEmpty) {
      val current = queue.dequeue()
      result += current
      dependedOnBy(current).foreach { dependent =>
        val newDeg = inDegree(dependent) - 1
        inDegree(dependent) = newDeg
        if (newDeg == 0) queue.enqueue(dependent)
      }
    }

    if (result.length != projects.length) {
      val remaining = projects.filterNot(result.contains)
      IO.raiseError(
        new RuntimeException(
          s"Circular dependency detected among monorepo projects: ${remaining.map(_.project).mkString(", ")}"
        )
      )
    } else {
      IO.pure(result.toSeq)
    }
  }
}
