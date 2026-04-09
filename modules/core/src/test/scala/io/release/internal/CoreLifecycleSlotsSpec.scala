package io.release.core.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleasePluginIO
import io.release.ReleaseResourceHooks
import io.release.TestRepoFiles
import io.release.core.internal.steps.ReleaseSteps
import io.release.runtime.engine.LifecycleCompiler
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
        ReleasePluginIO.autoImport.releaseIOPolicyEnableSnapshotDependenciesCheck.key.label,
        ReleasePluginIO.autoImport.releaseIOPolicyEnableRunClean.key.label,
        ReleasePluginIO.autoImport.releaseIOPolicyEnableRunTests.key.label,
        ReleasePluginIO.autoImport.releaseIOPolicyEnableTagging.key.label,
        ReleasePluginIO.autoImport.releaseIOPolicyEnablePublish.key.label,
        ReleasePluginIO.autoImport.releaseIOPolicyEnablePush.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksAfterCleanCheck.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksBeforeVersionResolution.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksAfterVersionResolution.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksBeforeReleaseVersionWrite.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksAfterReleaseVersionWrite.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksBeforeReleaseCommit.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksAfterReleaseCommit.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksBeforeTag.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksAfterTag.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksBeforePublish.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksAfterPublish.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksBeforeNextVersionWrite.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksAfterNextVersionWrite.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksBeforeNextCommit.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksAfterNextCommit.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksBeforePush.key.label,
        ReleasePluginIO.autoImport.releaseIOHooksAfterPush.key.label
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
    assertEquals(
      hookPhaseNames(CoreLifecycle.phases),
      CoreLifecycle.orderedHookDescriptors.map(_.phase)
    )
    assertEquals(
      CoreLifecycle.orderedHookDescriptors.map(_.phase),
      CoreLifecycleSlotsSpec.expectedHookPhases
    )
    assertEquals(CoreHookSlots.descriptors, CoreLifecycle.orderedHookDescriptors)
    assertEquals(
      CoreHookSlots.descriptors.map(_.slot.keyLabel),
      CoreLifecycleSlots.hookSlots.map(_.keyLabel)
    )
    assertEquals(
      builtInStepNames(CoreLifecycle.phases),
      ReleaseSteps.defaults.map(_.name)
    )
  }

  test("slot catalog - every CoreHookConfiguration field has a corresponding slot") {
    val configFieldCount =
      classOf[CoreHookConfiguration].getDeclaredFields.count(!_.isSynthetic)
    val bindingCount     = CoreLifecycleSlots.slots.size

    assertEquals(
      bindingCount,
      configFieldCount,
      s"CoreHookConfiguration has $configFieldCount fields but there are $bindingCount slots"
    )
  }

  test("slot catalog - each policy slot round-trips through its get/updated accessors") {
    CorePolicySlots.policySlots.foreach { slot =>
      val toggled = slot.updated(CoreHookConfiguration.empty, false)
      assert(
        !slot.get(toggled),
        s"Policy slot '${slot.id}' did not round-trip"
      )
    }
  }

  test("resource hook materialization order follows the lifecycle-derived descriptor order") {
    val assignments =
      ReleaseResourceHooks.hookAssignments(
        ReleaseResourceHooks.empty[Unit],
        (_: io.release.ReleaseResourceHookIO[Unit]) => ReleaseHookIO.action("unused")(_ => IO.unit)
      )

    assertEquals(
      assignments.map(_._1.keyLabel),
      CoreLifecycle.orderedHookDescriptors.map(_.slot.keyLabel)
    )
  }

  test("slot catalog - each hook slot round-trips through its get/updated accessors") {
    val sentinel = Seq(io.release.ReleaseHookIO("sentinel", ctx => cats.effect.IO.pure(ctx)))
    CoreHookSlots.hookSlots.foreach { slot =>
      val updated   = slot.updated(CoreHookConfiguration.empty, sentinel)
      val retrieved = slot.get(updated)
      assert(
        retrieved.nonEmpty,
        s"Hook slot '${slot.id}' did not round-trip: get after updated returned empty"
      )
    }
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
        "modules/core/src/main/scala/io/release/core/internal/CoreLifecycle.scala"
      )
    assert(!topLevelSource.contains("policySlot("))
    assert(!topLevelSource.contains("hookSlot("))
    assert(!topLevelSource.contains("hookPhase(\""))
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
