package io.release.internal

import org.specs2.mutable.Specification

class CoreReleasePlannerSpec extends Specification {

  "CoreReleasePlanner.build" should {

    "carry only stable flags and CLI overrides into the plan" in {
      val inputs = CoreReleasePlanner.Inputs(
        useDefaults = true,
        skipTests = true,
        skipPublish = false,
        interactive = true,
        crossBuild = true,
        releaseVersionOverride = Some("1.2.3"),
        nextVersionOverride = Some("1.2.4-SNAPSHOT"),
        tagDefault = Some("k")
      )

      val plan = CoreReleasePlanner.build(inputs)

      (plan.flags must_== ExecutionFlags(
        useDefaults = true,
        skipTests = true,
        skipPublish = false,
        interactive = true,
        crossBuild = true
      )) and
        (plan.releaseVersionOverride must beSome("1.2.3")) and
        (plan.nextVersionOverride must beSome("1.2.4-SNAPSHOT")) and
        (plan.tagDefault must beSome("k"))
    }

    "leave optional overrides empty when they are not provided" in {
      val plan = CoreReleasePlanner.build(
        CoreReleasePlanner.Inputs(
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

      (plan.releaseVersionOverride must beNone) and
        (plan.nextVersionOverride must beNone) and
        (plan.flags.interactive must beFalse) and
        (plan.flags.useDefaults must beFalse) and
        (plan.flags.skipPublish must beTrue) and
        (plan.tagDefault must beNone)
    }
  }
}
