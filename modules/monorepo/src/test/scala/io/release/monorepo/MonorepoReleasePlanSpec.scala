package io.release.monorepo

import cats.effect.IO
import io.release.TestAssertions.assertFailure
import io.release.internal.ExecutionFlags
import munit.CatsEffectSuite

class MonorepoReleasePlanSpec extends CatsEffectSuite {

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

  test("validateOverrideInputs - reject multiple global release-version overrides") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(globalReleaseVersions = Seq("1.0.0", "1.1.0"))
    )

    assertLeftContains(
      result,
      "Multiple global release-version overrides provided. Only one is allowed"
    )
  }

  test("validateOverrideInputs - reject multiple global next-version overrides") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(globalNextVersions = Seq("1.1.0-SNAPSHOT", "1.2.0-SNAPSHOT"))
    )

    assertLeftContains(
      result,
      "Multiple global next-version overrides provided. Only one is allowed"
    )
  }

  test("validateOverrideInputs - reject mixing global and per-project overrides") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(
        globalReleaseVersions = Seq("1.0.0"),
        nextVersionPairs = Seq("core" -> "1.1.0-SNAPSHOT")
      )
    )

    assertLeftContains(
      result,
      "Cannot mix global version overrides with per-project version overrides"
    )
  }

  test("validateOverrideInputs - reject explicit project selection combined with all-changed") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(allChanged = true, selectedNames = Seq("core"))
    )

    assertLeftContains(
      result,
      "Cannot combine 'all-changed' with explicit project selection"
    )
  }

  test("validateOverrideInputs - derive the explicit selection mode from validated inputs") {
    val plan = validatedPlan(
      baseInputs.copy(selectedNames = Seq("core"))
    )

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

  test("validateOverrideInputs - allow global overrides at startup") {
    val plan = validatedPlan(
      baseInputs.copy(globalReleaseVersions = Seq("1.0.0"))
    )

    assertEquals(plan.globalReleaseVersion, Some("1.0.0"))
  }

  test("validateOverrideInputs - preserve the configured command name") {
    val plan = validatedPlan(
      baseInputs.copy(commandName = "releaseMonorepoCustom")
    )

    assertEquals(plan.commandName, "releaseMonorepoCustom")
  }

  test("enforceGlobalVersionAllOrNothing - fail when subset in global version mode") {
    assertFailure[IllegalStateException, Seq[ProjectReleaseInfo]](
      MonorepoReleasePlan.enforceGlobalVersionAllOrNothing(
        allProjects = allProjects,
        changedProjects = allProjects.take(1),
        useGlobalVersion = true
      )
    )(err => assert(err.getMessage.contains("Global version mode is active")))
  }

  test("enforceGlobalVersionAllOrNothing - allow unchanged selection outside global mode") {
    MonorepoReleasePlan
      .enforceGlobalVersionAllOrNothing(
        allProjects = allProjects,
        changedProjects = allProjects.take(1),
        useGlobalVersion = false
      )
      .map(result => assertEquals(result, allProjects.take(1)))
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
    globalReleaseVersions = Nil,
    globalNextVersions = Nil,
    commandName = "releaseIOMonorepo"
  )

  private val allProjects = Seq(
    project("core"),
    project("api")
  )

  private def validatedPlan(inputs: MonorepoReleasePlan.Inputs): MonorepoReleasePlan =
    MonorepoReleasePlan.validateOverrideInputs(inputs) match {
      case Right(plan) => plan
      case Left(err)   => fail(s"Expected validated plan but got: $err")
    }

  private def assertLeftContains(
      result: Either[String, MonorepoReleasePlan],
      expected: String
  ): Unit = {
    result match {
      case Left(message) => assert(message.contains(expected))
      case Right(plan)   => fail(s"Expected validation failure containing '$expected' but got $plan")
    }
  }

  private def project(name: String): ProjectReleaseInfo =
    MonorepoTestSupport.dummyProject(name)
}
