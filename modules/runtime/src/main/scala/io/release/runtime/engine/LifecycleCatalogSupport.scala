package io.release.runtime.engine

private[release] object LifecycleCatalogSupport {

  def validateUniqueSlots[A](
      runtimeName: String,
      slots: Seq[A]
  )(
      slotId: A => String,
      keyLabel: A => String
  ): Vector[A] = {
    val duplicateSummaries = slots
      .groupBy(slotId)
      .collect {
        case (id, matching) if matching.length > 1 =>
          val labels = matching.map(keyLabel).distinct.sorted.mkString(", ")
          s"$id [$labels]"
      }
      .toVector
      .sorted

    if (duplicateSummaries.nonEmpty)
      throw new IllegalStateException(
        s"$runtimeName lifecycle slot catalog contains duplicate slot ids/key labels: " +
          duplicateSummaries.mkString(", ")
      )

    slots.toVector
  }
}
