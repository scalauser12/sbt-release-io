package io.release.internal

import io.release.ReleaseHookIO
import sbt.*

/** Core hook/policy settings resolved from a single sbt state snapshot. */
private[release] final case class CoreHookConfiguration(
    enableSnapshotDependenciesCheck: Boolean = true,
    enableRunClean: Boolean = true,
    enableRunTests: Boolean = true,
    enableTagging: Boolean = true,
    enablePublish: Boolean = true,
    enablePush: Boolean = true,
    afterCleanCheckHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeVersionResolutionHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterVersionResolutionHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeReleaseVersionWriteHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterReleaseVersionWriteHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeReleaseCommitHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterReleaseCommitHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeTagHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterTagHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforePublishHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterPublishHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeNextVersionWriteHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterNextVersionWriteHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforeNextCommitHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterNextCommitHooks: Seq[ReleaseHookIO] = Seq.empty,
    beforePushHooks: Seq[ReleaseHookIO] = Seq.empty,
    afterPushHooks: Seq[ReleaseHookIO] = Seq.empty
) {

  def hasCustomizations: Boolean =
    CoreHookConfiguration.hasCustomizations(this)

  def mergeWith(other: CoreHookConfiguration): CoreHookConfiguration =
    CoreHookConfiguration.merge(this, other)
}

private[release] object CoreHookConfiguration {

  val empty: CoreHookConfiguration = CoreHookConfiguration()

  lazy val defaultSettings: Seq[Setting[?]] =
    uniqueSlots.map(_.defaultSetting)

  def resolve(state: State): CoreHookConfiguration = {
    val extracted = SbtRuntime.extracted(state)

    uniqueSlots.foldLeft(empty) { (config, slot) =>
      slot.resolve(extracted, config)
    }
  }

  def merge(
      left: CoreHookConfiguration,
      right: CoreHookConfiguration
  ): CoreHookConfiguration =
    uniqueSlots.foldLeft(left) { (config, slot) =>
      slot.merge(config, right)
    }

  def hasCustomizations(config: CoreHookConfiguration): Boolean =
    uniqueSlots.exists(_.isCustomized(config))

  private def uniqueSlots: Vector[CoreConfigSlot] =
    CoreLifecycleSlots.slots
      .foldLeft((Vector.empty[CoreConfigSlot], Set.empty[String])) {
        case ((acc, seen), slot) if seen.contains(slot.id) => (acc, seen)
        case ((acc, seen), slot)                           => (acc :+ slot, seen + slot.id)
      }
      ._1
}
