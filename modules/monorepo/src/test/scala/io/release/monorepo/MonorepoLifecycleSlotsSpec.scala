package io.release.monorepo

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
        MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck.key.label,
        MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean.key.label,
        MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests.key.label,
        MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging.key.label,
        MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish.key.label,
        MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush.key.label,
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush.key.label
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
      MonorepoLifecycleSlotsSpec.expectedHookPhases
    )
    assertEquals(
      MonorepoGlobalHookSlots.descriptors.map(_.phase) ++
        MonorepoProjectHookSlots.descriptors.map(_.phase),
      Seq(
        "after-clean-check",
        "before-selection",
        "after-selection",
        "before-release-commit",
        "after-release-commit",
        "before-next-commit",
        "after-next-commit",
        "before-push",
        "after-push",
        "before-version-resolution",
        "after-version-resolution",
        "before-release-version-write",
        "after-release-version-write",
        "before-tag",
        "after-tag",
        "before-publish",
        "after-publish",
        "before-next-version-write",
        "after-next-version-write"
      )
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
