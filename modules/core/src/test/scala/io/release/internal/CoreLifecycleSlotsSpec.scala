package io.release.internal

import io.release.ReleaseContext
import io.release.ReleaseIO
import io.release.TestRepoFiles
import io.release.steps.ReleaseSteps
import munit.FunSuite

import java.nio.file.Files

class CoreLifecycleSlotsSpec extends FunSuite {

  test("slot catalog - keep unique slot ids") {
    val ids = CoreLifecycleSlots.slots.map(_.id)

    assertEquals(ids.distinct, ids)
  }

  test("slot catalog - represent every lifecycle-derived public key exactly once") {
    assertEquals(
      CoreLifecycleSlots.slots.map(_.keyLabel).sorted,
      Seq(
        ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck.key.label,
        ReleaseIO.releaseIOPolicyEnableRunClean.key.label,
        ReleaseIO.releaseIOPolicyEnableRunTests.key.label,
        ReleaseIO.releaseIOPolicyEnableTagging.key.label,
        ReleaseIO.releaseIOPolicyEnablePublish.key.label,
        ReleaseIO.releaseIOPolicyEnablePush.key.label,
        ReleaseIO.releaseIOHooksAfterCleanCheck.key.label,
        ReleaseIO.releaseIOHooksBeforeVersionResolution.key.label,
        ReleaseIO.releaseIOHooksAfterVersionResolution.key.label,
        ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite.key.label,
        ReleaseIO.releaseIOHooksAfterReleaseVersionWrite.key.label,
        ReleaseIO.releaseIOHooksBeforeReleaseCommit.key.label,
        ReleaseIO.releaseIOHooksAfterReleaseCommit.key.label,
        ReleaseIO.releaseIOHooksBeforeTag.key.label,
        ReleaseIO.releaseIOHooksAfterTag.key.label,
        ReleaseIO.releaseIOHooksBeforePublish.key.label,
        ReleaseIO.releaseIOHooksAfterPublish.key.label,
        ReleaseIO.releaseIOHooksBeforeNextVersionWrite.key.label,
        ReleaseIO.releaseIOHooksAfterNextVersionWrite.key.label,
        ReleaseIO.releaseIOHooksBeforeNextCommit.key.label,
        ReleaseIO.releaseIOHooksAfterNextCommit.key.label,
        ReleaseIO.releaseIOHooksBeforePush.key.label,
        ReleaseIO.releaseIOHooksAfterPush.key.label
      ).sorted
    )
  }

  test("slot catalog - drive the lifecycle-derived default settings exactly once") {
    assertEquals(
      CoreLifecycle.configDefaultSettings.map(_.key.key.label).sorted,
      CoreLifecycleSlots.slots.map(_.keyLabel).sorted
    )
  }

  test("slot catalog - grouped inventories concatenate into the aggregate inventory in order") {
    assertEquals(CoreLifecycleSlots.policySlots, CorePolicySlots.policySlots)
    assertEquals(CoreLifecycleSlots.hookSlots, CoreHookSlots.hookSlots)
    assertEquals(
      CoreLifecycleSlots.slots,
      CorePolicySlots.policySlots ++ CoreHookSlots.hookSlots
    )
  }

  test("slot-backed phases - preserve canonical phase and built-in step names") {
    assertEquals(hookPhaseNames(CoreLifecycle.phases), CoreLifecycleSlotsSpec.expectedHookPhases)
    assertEquals(
      builtInStepNames(CoreLifecycle.phases),
      ReleaseSteps.defaults.map(_.name)
    )
  }

  test("source cleanup - standalone lifecycle slot and hook materialization helpers are gone") {
    val repoRoot = TestRepoFiles.resolve("build.sbt").getParent

    assert(
      Files.notExists(
        repoRoot.resolve(
          "modules/core/src/main/scala/io/release/internal/LifecycleSlotSupport.scala"
        )
      )
    )
    assert(
      Files.notExists(
        repoRoot.resolve(
          "modules/core/src/main/scala/io/release/internal/HookMaterializationSupport.scala"
        )
      )
    )
    val topLevelSource =
      TestRepoFiles.readString(
        "modules/core/src/main/scala/io/release/internal/CoreLifecycle.scala"
      )
    assert(!topLevelSource.contains("policySlot("))
    assert(!topLevelSource.contains("hookSlot("))
  }

  private def hookPhaseNames(
      phases: Seq[LifecycleCompiler.Phase[CoreHookConfiguration, ReleaseContext, Nothing]]
  ): Seq[String] =
    phases.flatMap(_.phaseName)

  private def builtInStepNames(
      phases: Seq[LifecycleCompiler.Phase[CoreHookConfiguration, ReleaseContext, Nothing]]
  ): Seq[String] =
    LifecycleCompiler.defaultsSingle(phases).map(_.name)
}

object CoreLifecycleSlotsSpec {
  val expectedHookPhases: Seq[String] = Seq(
    "after-clean-check",
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
