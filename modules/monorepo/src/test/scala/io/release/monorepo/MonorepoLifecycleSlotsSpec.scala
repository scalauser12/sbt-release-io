package io.release.monorepo

import io.release.monorepo.internal.*
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.runtime.engine.LifecycleCompiler
import munit.FunSuite

class MonorepoLifecycleSlotsSpec extends FunSuite {

  test("phases - preserve compiled hook phase and built-in step order") {
    assertEquals(
      hookPhaseNames(MonorepoLifecycle.phases),
      MonorepoLifecycleSlotsSpec.expectedHookPhases
    )
    assertEquals(
      builtInStepNames(MonorepoLifecycle.phases),
      MonorepoReleaseSteps.defaults.map(_.name)
    )
  }

  test("phases - default settings cover all expected keys") {
    val settingKeys =
      MonorepoLifecycle.configDefaultSettings
        .map(_.key.key.label)
        .sorted

    assert(settingKeys.nonEmpty)
    // 6 policy + 19 hook = 25 settings
    assertEquals(settingKeys.size, 25)
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
