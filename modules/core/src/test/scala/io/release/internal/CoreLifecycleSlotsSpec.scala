package io.release.core.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleasePluginIO
import io.release.ReleaseResourceHooks
import io.release.core.internal.steps.ReleaseSteps
import io.release.runtime.engine.LifecycleCompiler
import munit.FunSuite

class CoreLifecycleSlotsSpec extends FunSuite {

  test("catalog - keep lifecycle-derived key labels unique") {
    assertEquals(
      CoreLifecycle.slots.map(_.keyLabel).sorted,
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

  test("catalog - drive default settings from the canonical slot list") {
    assertEquals(
      CoreLifecycle.configDefaultSettings.map(_.key.key.label).sorted,
      CoreLifecycle.slots.map(_.keyLabel).sorted
    )
  }

  test("catalog - preserve compiled hook phase and built-in step order") {
    assertEquals(
      hookPhaseNames(CoreLifecycle.phases),
      CoreLifecycle.orderedHookDescriptors.map(_.phase)
    )
    assertEquals(
      CoreLifecycle.orderedHookDescriptors.map(_.phase),
      CoreLifecycleSlotsSpec.expectedHookPhases
    )
    assertEquals(
      builtInStepNames(CoreLifecycle.phases),
      ReleaseSteps.defaults.map(_.name)
    )
  }

  test("catalog - preserve resource hook materialization order") {
    val assignments =
      ReleaseResourceHooks.hookAssignments(
        ReleaseResourceHooks.empty[Unit],
        (_: io.release.ReleaseResourceHookIO[Unit]) => ReleaseHookIO.action("unused")(_ => IO.unit)
      )

    assertEquals(
      assignments.map(_._1.keyLabel),
      CoreLifecycle.orderedHookDescriptors.map(_.keyLabel)
    )
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
