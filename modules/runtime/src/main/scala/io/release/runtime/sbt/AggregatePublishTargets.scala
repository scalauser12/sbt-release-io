package io.release.runtime.sbt

import _root_.sbt.{internal as _, *}

/** Resolve the projects that sbt task aggregation reaches for a given task key.
  *
  * Mirrors sbt's `runAggregated` expansion: walks the aggregate tree from
  * `extracted.currentRef`, honoring per-project `aggregate := false` by stopping
  * descent at any project whose `aggregationEnabled` is `false`.
  *
  * Core publish filters this expansion by `publish / skip`, then evaluates the eligible
  * scoped actions directly in one selected task graph. The commit/tag steps use the same
  * expansion to install per-`ProjectRef` manifest metadata so child artifacts carry the
  * same `Vcs-Release-Hash` / `Vcs-Release-Tag` entries as the root.
  */
private[release] object AggregatePublishTargets {

  def fromState[A](state: State, taskKey: TaskKey[A]): Seq[ProjectRef] = {
    val extracted = SbtRuntime.extracted(state)
    val data      = extracted.structure.data
    val rootKey   = (extracted.currentRef / taskKey).scopedKey
    val enabled   = sbt.internal.Aggregation.aggregationEnabled(rootKey, data)
    if (!enabled) Seq(extracted.currentRef)
    else {
      val units                                           = extracted.structure.units
      def resolve(ref: ProjectRef): Seq[ProjectRef]       = {
        val project = units.get(ref.build).flatMap(_.defined.get(ref.project))
        project.map(_.aggregate).getOrElse(Seq.empty)
      }
      def aggregationEnabledFor(ref: ProjectRef): Boolean =
        sbt.internal.Aggregation.aggregationEnabled((ref / taskKey).scopedKey, data)
      def loop(
          refs: Seq[ProjectRef],
          visited: Set[ProjectRef]
      ): (Seq[ProjectRef], Set[ProjectRef])               =
        refs.foldLeft((Seq.empty[ProjectRef], visited)) { case ((acc, vis), ref) =>
          if (vis.contains(ref)) (acc, vis)
          else if (!aggregationEnabledFor(ref)) (acc :+ ref, vis + ref)
          else {
            val (childAcc, childVis) = loop(resolve(ref), vis + ref)
            (acc ++ (ref +: childAcc), childVis)
          }
        }
      extracted.currentRef +: loop(resolve(extracted.currentRef), Set(extracted.currentRef))._1
    }
  }
}
