package io.release.core.internal

import io.release.ReleaseContext
import io.release.core.internal.steps.ReleaseSteps
import io.release.runtime.engine.LifecycleCompiler
import munit.FunSuite

class CoreLifecycleSlotsSpec extends FunSuite {

  test("phases - preserve compiled hook phase and built-in step order") {
    assertEquals(
      hookPhaseNames(CoreLifecycle.phases),
      CoreLifecycleSlotsSpec.expectedHookPhases
    )
    assertEquals(
      builtInStepNames(CoreLifecycle.phases),
      ReleaseSteps.defaults.map(_.name)
    )
  }

  test("phases - default settings cover all expected keys") {
    val settingKeys =
      CoreLifecycle.configDefaultSettings.map(_.key.key.label).sorted

    assert(settingKeys.nonEmpty)
    // 6 policy + 17 hook = 23 settings
    assertEquals(settingKeys.size, 23)
  }

  private def hookPhaseNames(
      phases: Seq[
        LifecycleCompiler.Phase[
          CoreHookConfiguration,
          ReleaseContext,
          Nothing
        ]
      ]
  ): Seq[String] =
    phases.flatMap(_.phaseName)

  private def builtInStepNames(
      phases: Seq[
        LifecycleCompiler.Phase[
          CoreHookConfiguration,
          ReleaseContext,
          Nothing
        ]
      ]
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
