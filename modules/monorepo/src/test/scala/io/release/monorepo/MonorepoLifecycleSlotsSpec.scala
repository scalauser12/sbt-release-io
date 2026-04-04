package io.release.monorepo

import io.release.internal.LifecycleCompiler
import io.release.TestRepoFiles
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
