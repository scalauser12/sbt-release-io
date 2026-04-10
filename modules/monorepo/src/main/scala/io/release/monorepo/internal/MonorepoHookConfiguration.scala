package io.release.monorepo.internal

import io.release.monorepo.*

import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.engine.LifecycleCatalogSupport
import sbt.*

/** Monorepo hook/policy settings resolved from a single sbt state snapshot. */
private[monorepo] final case class MonorepoHookConfiguration(
    enableSnapshotDependenciesCheck: Boolean = true,
    enableRunClean: Boolean = true,
    enableRunTests: Boolean = true,
    enableTagging: Boolean = true,
    enablePublish: Boolean = true,
    enablePush: Boolean = true,
    afterCleanCheckHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    beforeSelectionHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    afterSelectionHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    beforeVersionResolutionHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterVersionResolutionHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforeReleaseVersionWriteHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterReleaseVersionWriteHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforeReleaseCommitHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    afterReleaseCommitHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    beforeTagHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterTagHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforePublishHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterPublishHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforeNextVersionWriteHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    afterNextVersionWriteHooks: Seq[MonorepoProjectHookIO] = Seq.empty,
    beforeNextCommitHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    afterNextCommitHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    beforePushHooks: Seq[MonorepoGlobalHookIO] = Seq.empty,
    afterPushHooks: Seq[MonorepoGlobalHookIO] = Seq.empty
) {

  def mergeWith(other: MonorepoHookConfiguration): MonorepoHookConfiguration =
    MonorepoHookConfiguration.merge(this, other)

  def hasCustomizations: Boolean =
    MonorepoHookConfiguration.hasCustomizations(this)
}

private[monorepo] object MonorepoHookConfiguration {

  val empty: MonorepoHookConfiguration = MonorepoHookConfiguration()

  lazy val defaultSettings: Seq[Setting[?]] =
    validatedSlots.map(_.defaultSetting)

  def resolve(state: State): MonorepoHookConfiguration = {
    val extracted = SbtRuntime.extracted(state)

    validatedSlots.foldLeft(empty) { (config, slot) =>
      slot.resolve(extracted, config)
    }
  }

  def merge(
      left: MonorepoHookConfiguration,
      right: MonorepoHookConfiguration
  ): MonorepoHookConfiguration =
    validatedSlots.foldLeft(left) { (config, slot) =>
      slot.merge(config, right)
    }

  def hasCustomizations(config: MonorepoHookConfiguration): Boolean =
    validatedSlots.exists(_.isCustomized(config))

  private lazy val validatedSlots: Vector[MonorepoConfigSlot] =
    LifecycleCatalogSupport.validateUniqueSlots("monorepo", MonorepoLifecycle.slots)(
      _.id,
      _.keyLabel
    )
}
