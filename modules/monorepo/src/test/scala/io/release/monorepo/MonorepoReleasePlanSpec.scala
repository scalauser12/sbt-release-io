package io.release.monorepo

import cats.effect.unsafe.implicits.global
import io.release.internal.ExecutionFlags
import munit.FunSuite
import sbt.ProjectRef

import java.io.File
import java.net.URI

class MonorepoReleasePlanSpec extends FunSuite {

  test("validateOverrideInputs - reject duplicate per-project release-version overrides") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(releaseVersionPairs = Seq("core" -> "1.0.0", "core" -> "1.1.0"))
    )

    assert(result.isLeft)
    assert(result.left.exists(_.contains("Duplicate per-project release-version overrides")))
  }

  test("validateOverrideInputs - reject explicit project selection combined with all-changed") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(allChanged = true, selectedNames = Seq("core"))
    )

    assert(result.isLeft)
    assert(
      result.left.exists(
        _.contains("Cannot combine 'all-changed' with explicit project selection")
      )
    )
  }

  test("validateOverrideInputs - derive the explicit selection mode from validated inputs") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(selectedNames = Seq("core"))
    )

    assert(result.isRight)
    result.foreach { validated =>
      assertEquals(validated.selectionMode, SelectionMode.ExplicitSelection)
    }
  }

  test("validateOverrideInputs - allow global overrides at startup") {
    val result = MonorepoReleasePlan.validateOverrideInputs(
      baseInputs.copy(globalReleaseVersions = Seq("1.0.0"))
    )

    assert(result.isRight)
    result.foreach { validated =>
      assertEquals(validated.globalReleaseVersion, Some("1.0.0"))
    }
  }

  test("enforceGlobalVersionAllOrNothing - fail when subset in global version mode") {
    val result =
      MonorepoReleasePlan
        .enforceGlobalVersionAllOrNothing(
          allProjects = allProjects,
          changedProjects = allProjects.take(1),
          useGlobalVersion = true
        )
        .attempt
        .unsafeRunSync()

    assert(result.isLeft)
    result.left.foreach {
      case err: IllegalStateException =>
        assert(err.getMessage.contains("Global version mode is active"))
      case other                      =>
        fail(s"Expected IllegalStateException but got $other")
    }
  }

  test("enforceGlobalVersionAllOrNothing - allow unchanged selection outside global mode") {
    val result = MonorepoReleasePlan
      .enforceGlobalVersionAllOrNothing(
        allProjects = allProjects,
        changedProjects = allProjects.take(1),
        useGlobalVersion = false
      )
      .unsafeRunSync()

    assertEquals(result, allProjects.take(1))
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
    globalNextVersions = Nil
  )

  private val allProjects = Seq(
    project("core"),
    project("api")
  )

  private def project(name: String): ProjectReleaseInfo =
    MonorepoTestSupport.dummyProject(name)
}
