package io.release.monorepo

import io.release.monorepo.internal.*

import cats.effect.IO
import io.release.runtime.engine.LifecycleCompiler
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import munit.FunSuite

class MonorepoLifecycleSlotsSpec extends FunSuite {

  test("catalog - keep lifecycle-derived key labels unique") {
    assertEquals(
      MonorepoLifecycle.slots.map(_.keyLabel).sorted,
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

  test("catalog - drive default settings from the canonical slot list") {
    assertEquals(
      MonorepoLifecycle.configDefaultSettings.map(_.key.key.label).sorted,
      MonorepoLifecycle.slots.map(_.keyLabel).sorted
    )
  }

  test("catalog - preserve compiled hook phase and built-in step order") {
    assertEquals(
      hookPhaseNames(MonorepoLifecycle.phases),
      MonorepoLifecycle.orderedHookPhases
    )
    assertEquals(
      MonorepoLifecycle.orderedHookPhases,
      MonorepoLifecycleSlotsSpec.expectedHookPhases
    )
    assertEquals(
      builtInStepNames(MonorepoLifecycle.phases),
      MonorepoReleaseSteps.defaults.map(_.name)
    )
  }

  test("catalog - preserve resource hook materialization order for global hooks") {
    val assignments =
      MonorepoResourceHooks.globalHookAssignments(
        MonorepoResourceHooks.empty[Unit],
        (_: MonorepoGlobalResourceHookIO[Unit]) =>
          MonorepoGlobalHookIO.action("unused")(_ => IO.unit)
      )

    assertEquals(
      assignments.map(_._1.keyLabel),
      MonorepoLifecycle.orderedGlobalHookDescriptors.map(_.keyLabel)
    )
  }

  test("catalog - preserve resource hook materialization order for project hooks") {
    val assignments =
      MonorepoResourceHooks.projectHookAssignments(
        MonorepoResourceHooks.empty[Unit],
        (_: MonorepoProjectResourceHookIO[Unit]) =>
          MonorepoProjectHookIO.action("unused")((_, _) => IO.unit)
      )

    assertEquals(
      assignments.map(_._1.keyLabel),
      MonorepoLifecycle.orderedProjectHookDescriptors.map(_.keyLabel)
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
