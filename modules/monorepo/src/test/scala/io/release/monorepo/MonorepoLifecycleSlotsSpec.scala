package io.release.monorepo

import cats.effect.IO
import io.release.TestRepoFiles
import io.release.internal.LifecycleCompiler
import io.release.monorepo.steps.MonorepoReleaseSteps
import munit.FunSuite

class MonorepoLifecycleSlotsSpec extends FunSuite {

  test("slot catalog - keep unique slot ids") {
    val ids = MonorepoLifecycleSlots.slots.map(_.id)

    assertEquals(ids.distinct, ids)
  }

  test("slot catalog - represent every lifecycle-derived public key exactly once") {
    assertEquals(
      MonorepoLifecycleSlots.slots.map(_.keyLabel).sorted,
      Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunClean.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunTests.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableTagging.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePublish.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePush.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterCleanCheck.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterSelection.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeVersionResolution.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterVersionResolution.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeReleaseVersionWrite.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterReleaseVersionWrite.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeReleaseCommit.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterReleaseCommit.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeTag.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterTag.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePublish.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPublish.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeNextVersionWrite.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextVersionWrite.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeNextCommit.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextCommit.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePush.key.label,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPush.key.label
      ).sorted
    )
  }

  test("slot catalog - drive the lifecycle-derived default settings exactly once") {
    assertEquals(
      MonorepoLifecycle.configDefaultSettings.map(_.key.key.label).sorted,
      MonorepoLifecycleSlots.slots.map(_.keyLabel).sorted
    )
  }

  test("slot catalog - grouped inventories concatenate into the aggregate inventory in order") {
    assertEquals(MonorepoLifecycleSlots.policySlots, MonorepoPolicySlots.policySlots)
    assertEquals(MonorepoLifecycleSlots.globalHookSlots, MonorepoGlobalHookSlots.globalHookSlots)
    assertEquals(
      MonorepoLifecycleSlots.projectHookSlots,
      MonorepoProjectHookSlots.projectHookSlots
    )
    assertEquals(
      MonorepoLifecycleSlots.slots,
      MonorepoPolicySlots.policySlots ++
        MonorepoGlobalHookSlots.globalHookSlots ++
        MonorepoProjectHookSlots.projectHookSlots
    )
  }

  test("slot-backed phases - preserve canonical phase and built-in step names") {
    assertEquals(
      hookPhaseNames(MonorepoLifecycle.phases),
      MonorepoLifecycle.orderedHookPhases
    )
    assertEquals(
      MonorepoLifecycle.orderedHookPhases,
      MonorepoLifecycleSlotsSpec.expectedHookPhases
    )
    assertEquals(
      MonorepoGlobalHookSlots.descriptors,
      MonorepoLifecycle.orderedGlobalHookDescriptors
    )
    assertEquals(
      MonorepoProjectHookSlots.descriptors,
      MonorepoLifecycle.orderedProjectHookDescriptors
    )
    assertEquals(
      MonorepoGlobalHookSlots.descriptors.map(_.slot.keyLabel),
      MonorepoLifecycleSlots.globalHookSlots.map(_.keyLabel)
    )
    assertEquals(
      MonorepoProjectHookSlots.descriptors.map(_.slot.keyLabel),
      MonorepoLifecycleSlots.projectHookSlots.map(_.keyLabel)
    )
    assertEquals(
      builtInStepNames(MonorepoLifecycle.phases),
      MonorepoReleaseSteps.defaults.map(_.name)
    )
  }

  test(
    "resource hook materialization order follows the lifecycle-derived global descriptor order"
  ) {
    val assignments =
      MonorepoResourceHooks.globalHookAssignments(
        MonorepoResourceHooks.empty[Unit],
        (_: MonorepoGlobalResourceHookIO[Unit]) =>
          MonorepoGlobalHookIO.action("unused")(_ => IO.unit)
      )

    assertEquals(
      assignments.map(_._1.keyLabel),
      MonorepoLifecycle.orderedGlobalHookDescriptors.map(_.slot.keyLabel)
    )
  }

  test(
    "resource hook materialization order follows the lifecycle-derived project descriptor order"
  ) {
    val assignments =
      MonorepoResourceHooks.projectHookAssignments(
        MonorepoResourceHooks.empty[Unit],
        (_: MonorepoProjectResourceHookIO[Unit]) =>
          MonorepoProjectHookIO.action("unused")((_, _) => IO.unit)
      )

    assertEquals(
      assignments.map(_._1.keyLabel),
      MonorepoLifecycle.orderedProjectHookDescriptors.map(_.slot.keyLabel)
    )
  }

  private def hookPhaseNames(
      phases: Seq[
        LifecycleCompiler.Phase[
          MonorepoHookConfiguration,
          MonorepoContext,
          ProjectReleaseInfo
        ]
      ]
  ): Seq[String] =
    phases.flatMap(_.phaseName)

  private def builtInStepNames(
      phases: Seq[
        LifecycleCompiler.Phase[
          MonorepoHookConfiguration,
          MonorepoContext,
          ProjectReleaseInfo
        ]
      ]
  ): Seq[String] =
    LifecycleCompiler.defaults(phases).map(_.name)

  test("slot catalog - every MonorepoHookConfiguration field has a corresponding slot") {
    val configFieldCount =
      classOf[MonorepoHookConfiguration].getDeclaredFields.count(!_.isSynthetic)
    val bindingCount     = MonorepoLifecycleSlots.slots.size

    assertEquals(
      bindingCount,
      configFieldCount,
      s"MonorepoHookConfiguration has $configFieldCount fields but there are $bindingCount slots"
    )
  }

  test("slot catalog - each policy slot round-trips through its get/updated accessors") {
    MonorepoPolicySlots.policySlots.foreach { slot =>
      val toggled = slot.updated(MonorepoHookConfiguration.empty, false)
      assert(
        !slot.get(toggled),
        s"Policy slot '${slot.id}' did not round-trip"
      )
    }
  }

  test("slot catalog - each global hook slot round-trips through its get/updated accessors") {
    val sentinel = Seq(MonorepoGlobalHookIO("sentinel", ctx => cats.effect.IO.pure(ctx)))
    MonorepoGlobalHookSlots.globalHookSlots.foreach { slot =>
      val updated   = slot.updated(MonorepoHookConfiguration.empty, sentinel)
      val retrieved = slot.get(updated)
      assert(
        retrieved.nonEmpty,
        s"Global hook slot '${slot.id}' did not round-trip"
      )
    }
  }

  test("slot catalog - each project hook slot round-trips through its get/updated accessors") {
    val sentinel = Seq(MonorepoProjectHookIO("sentinel", (ctx, _) => cats.effect.IO.pure(ctx)))
    MonorepoProjectHookSlots.projectHookSlots.foreach { slot =>
      val updated   = slot.updated(MonorepoHookConfiguration.empty, sentinel)
      val retrieved = slot.get(updated)
      assert(
        retrieved.nonEmpty,
        s"Project hook slot '${slot.id}' did not round-trip"
      )
    }
  }

  test("source cleanup - top-level slot facade no longer defines slots inline") {
    val topLevelSource =
      TestRepoFiles.readString(
        "modules/monorepo/src/main/scala/io/release/monorepo/MonorepoLifecycle.scala"
      )

    assert(!topLevelSource.contains("policySlot("))
    assert(!topLevelSource.contains("hookSlot("))
    assert(!topLevelSource.contains("globalHookPhase(\""))
    assert(!topLevelSource.contains("projectHookPhase(\""))
  }
}

object MonorepoLifecycleSlotsSpec {
  val expectedHookPhases: Seq[String] = Seq(
    "after-clean-check",
    "before-selection",
    "after-selection",
    "before-version-resolution",
    "after-version-resolution",
    "before-release-version-write",
    "after-release-version-write",
    "before-release-commit",
    "after-release-commit",
    "before-tag",
    "after-tag",
    "before-publish",
    "after-publish",
    "before-next-version-write",
    "after-next-version-write",
    "before-next-commit",
    "after-next-commit",
    "before-push",
    "after-push"
  )
}
