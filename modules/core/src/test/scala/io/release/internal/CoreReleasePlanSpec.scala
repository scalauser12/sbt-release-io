package io.release.internal

import munit.FunSuite

class CoreReleasePlanSpec extends FunSuite {

  test("CoreReleasePlan - carry only stable flags and CLI overrides into the plan") {
    val plan = CoreReleasePlan.fromFlags(
      useDefaults = true,
      skipTests = true,
      skipPublish = false,
      interactive = true,
      crossBuild = true,
      releaseVersionOverride = Some("1.2.3"),
      nextVersionOverride = Some("1.2.4-SNAPSHOT"),
      decisionDefaults = ReleaseDecisionDefaults.empty.copy(tagExistsAnswer = Some("k")),
      commandName = "releaseCustom"
    )

    assertEquals(
      plan.flags,
      ExecutionFlags(
        useDefaults = true,
        skipTests = true,
        skipPublish = false,
        interactive = true,
        crossBuild = true
      )
    )
    assertEquals(plan.releaseVersionOverride, Some("1.2.3"))
    assertEquals(plan.nextVersionOverride, Some("1.2.4-SNAPSHOT"))
    assertEquals(plan.decisionDefaults.tagExistsAnswer, Some("k"))
    assertEquals(plan.commandName, "releaseCustom")
  }

  test("CoreReleasePlan - leave optional overrides empty when they are not provided") {
    val plan = CoreReleasePlan.fromFlags(
      useDefaults = false,
      skipTests = false,
      skipPublish = true,
      interactive = false,
      crossBuild = false,
      releaseVersionOverride = None,
      nextVersionOverride = None,
      decisionDefaults = ReleaseDecisionDefaults.empty
    )

    assertEquals(plan.releaseVersionOverride, None)
    assertEquals(plan.nextVersionOverride, None)
    assert(!plan.flags.interactive)
    assert(!plan.flags.useDefaults)
    assert(plan.flags.skipPublish)
    assertEquals(plan.decisionDefaults, ReleaseDecisionDefaults.empty)
  }
}
