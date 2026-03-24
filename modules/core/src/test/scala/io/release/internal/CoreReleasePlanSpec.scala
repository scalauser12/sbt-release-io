package io.release.internal

import munit.FunSuite

class CoreReleasePlanSpec extends FunSuite {

  test("CoreReleasePlan.build - carry only stable flags and CLI overrides into the plan") {
    val inputs = CoreReleasePlan.Inputs(
      useDefaults = true,
      skipTests = true,
      skipPublish = false,
      interactive = true,
      crossBuild = true,
      releaseVersionOverride = Some("1.2.3"),
      nextVersionOverride = Some("1.2.4-SNAPSHOT"),
      tagDefault = Some("k"),
      commandName = "releaseCustom"
    )

    val plan = CoreReleasePlan.build(inputs)

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
    assertEquals(plan.tagDefault, Some("k"))
    assertEquals(plan.commandName, "releaseCustom")
  }

  test("CoreReleasePlan.build - leave optional overrides empty when they are not provided") {
    val plan = CoreReleasePlan.build(
      CoreReleasePlan.Inputs(
        useDefaults = false,
        skipTests = false,
        skipPublish = true,
        interactive = false,
        crossBuild = false,
        releaseVersionOverride = None,
        nextVersionOverride = None,
        tagDefault = None
      )
    )

    assertEquals(plan.releaseVersionOverride, None)
    assertEquals(plan.nextVersionOverride, None)
    assert(!plan.flags.interactive)
    assert(!plan.flags.useDefaults)
    assert(plan.flags.skipPublish)
    assertEquals(plan.tagDefault, None)
  }
}
