package io.release.monorepo

import io.release.internal.ExecutionFlags
import munit.FunSuite

class MonorepoReleasePlanSpec extends FunSuite {

  test("validateOverrideInputs - reject invalid release-version pair format") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(releaseVersionPairs = Seq("" -> "1.0.0"))
    )

    assertLeftContains(result, "Invalid release-version format. Expected project=version")
  }

  test("validateOverrideInputs - reject invalid next-version pair format") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(nextVersionPairs = Seq("core" -> ""))
    )

    assertLeftContains(result, "Invalid next-version format. Expected project=version")
  }

  test("validateOverrideInputs - reject duplicate per-project release-version overrides") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(releaseVersionPairs = Seq("core" -> "1.0.0", "core" -> "1.1.0"))
    )

    assertLeftContains(result, "Duplicate per-project release-version overrides")
  }

  test("validateOverrideInputs - reject duplicate per-project next-version overrides") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(nextVersionPairs =
        Seq("core" -> "1.1.0-SNAPSHOT", "core" -> "1.2.0-SNAPSHOT")
      )
    )

    assertLeftContains(result, "Duplicate per-project next-version overrides")
  }

  test("validateOverrideInputs - reject explicit project selection combined with all-changed") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(allChanged = true, selectedNames = Seq("core"))
    )

    assertLeftContains(result, "Cannot combine 'all-changed' with explicit project selection")
  }

  test("validateOverrideInputs - derive the explicit selection mode from validated inputs") {
    val plan = validatedPlan(baseInputs.copy(selectedNames = Seq("core")))

    assertEquals(plan.selectionMode, SelectionMode.ExplicitSelection)
  }

  test("validateOverrideInputs - derive the all-changed selection mode when requested") {
    val plan = validatedPlan(baseInputs.copy(allChanged = true))

    assertEquals(plan.selectionMode, SelectionMode.AllChanged)
  }

  test("validateOverrideInputs - derive detect-changes selection mode by default") {
    val plan = validatedPlan(baseInputs)

    assertEquals(plan.selectionMode, SelectionMode.DetectChanges)
  }

  test("validateOverrideInputs - preserve validated per-project overrides") {
    val plan = validatedPlan(
      baseInputs.copy(
        releaseVersionPairs = Seq("core" -> "1.0.0"),
        nextVersionPairs = Seq("core" -> "1.1.0-SNAPSHOT")
      )
    )

    assertEquals(plan.releaseVersionOverrides, Map("core" -> "1.0.0"))
    assertEquals(plan.nextVersionOverrides, Map("core" -> "1.1.0-SNAPSHOT"))
  }

  test("validateOverrideInputs - preserve the configured command name") {
    val plan = validatedPlan(baseInputs.copy(commandName = "releaseMonorepoCustom"))

    assertEquals(plan.commandName, "releaseMonorepoCustom")
  }

  private val baseInputs = MonorepoReleasePlan.Inputs(
    flags = ExecutionFlags(
      useDefaults = false,
      skipTests = false,
      skipPublish = false,
      interactive = false,
      crossBuild = false
    ),
    allChanged = false,
    selectedNames = Nil,
    releaseVersionPairs = Nil,
    nextVersionPairs = Nil,
    commandName = "releaseIOMonorepo"
  )

  private def validatedPlan(inputs: MonorepoReleasePlan.Inputs): MonorepoReleasePlan =
    MonorepoReleasePlan.validateOverrideInputs(inputs) match {
      case Right(plan) => plan
      case Left(err)   => fail(s"Expected validated plan but got: $err")
    }

  private def assertLeftContains(
      result: Either[String, MonorepoReleasePlan],
      expected: String
  ): Unit =
    result match {
      case Left(message) => assert(message.contains(expected))
      case Right(plan)   => fail(s"Expected validation failure containing '$expected' but got $plan")
    }
}
