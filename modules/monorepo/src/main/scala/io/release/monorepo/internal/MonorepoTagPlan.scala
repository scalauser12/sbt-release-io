package io.release.monorepo.internal

import sbt.ProjectRef

/** Immutable tag-name reservations for one selected-project release batch.
  *
  * Selected batches are intentionally small, so keeping the ordered plan as the
  * source of truth is simpler than maintaining parallel forward and reverse
  * indexes. The order also makes conflict diagnostics deterministic.
  */
private[monorepo] final case class MonorepoTagPlan private (
    entries: Vector[MonorepoTagPlan.Entry],
    resolvedByRef: Map[ProjectRef, String]
) {

  def plannedName(ref: ProjectRef): Option[String] =
    entries.find(_.ref == ref).map(_.tagName)

  def conflicts(ref: ProjectRef, candidate: String): Vector[String] =
    entries.collect {
      case entry
          if entry.ref != ref &&
            (entry.tagName == candidate || resolvedByRef.get(entry.ref).contains(candidate)) =>
        entry.label
    }

  def recordResolved(ref: ProjectRef, tagName: String): MonorepoTagPlan =
    if (entries.exists(_.ref == ref) && !resolvedByRef.get(ref).contains(tagName))
      copy(resolvedByRef = resolvedByRef.updated(ref, tagName))
    else this
}

private[monorepo] object MonorepoTagPlan {

  final case class Entry(
      ref: ProjectRef,
      label: String,
      tagName: String
  )

  def from(entries: Seq[Entry]): MonorepoTagPlan =
    MonorepoTagPlan(entries.toVector, Map.empty)
}
