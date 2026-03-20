package io.release.monorepo

import io.release.internal.ExecutionFlags
import munit.FunSuite
import sbt.ProjectRef

import java.io.File
import java.net.URI

class MonorepoProjectResolverSpec extends FunSuite {

  test("applyVersionOverrides - apply per-project overrides in non-global mode") {
    val plan   = MonorepoReleasePlan(
      flags = ExecutionFlags(false, false, false, false, false),
      selectionMode = SelectionMode.ExplicitSelection,
      selectedNames = Seq("core"),
      releaseVersionOverrides = Map("core" -> "1.0.0"),
      nextVersionOverrides = Map("core" -> "1.1.0-SNAPSHOT"),
      globalReleaseVersion = None,
      globalNextVersion = None
    )
    val result = MonorepoProjectResolver.applyVersionOverrides(
      Seq(project("core"), project("api")),
      plan,
      useGlobalVersion = false
    )
    val core   = result.find(_.name == "core").get
    val api    = result.find(_.name == "api").get

    assertEquals(core.versions, Some("1.0.0" -> "1.1.0-SNAPSHOT"))
    assertEquals(api.versions, None)
  }

  test("applyVersionOverrides - apply global overrides to every project in global mode") {
    val plan   = MonorepoReleasePlan(
      flags = ExecutionFlags(false, false, false, false, false),
      selectionMode = SelectionMode.AllChanged,
      selectedNames = Nil,
      releaseVersionOverrides = Map.empty,
      nextVersionOverrides = Map.empty,
      globalReleaseVersion = Some("2.0.0"),
      globalNextVersion = Some("2.1.0-SNAPSHOT")
    )
    val result = MonorepoProjectResolver.applyVersionOverrides(
      Seq(project("core"), project("api")),
      plan,
      useGlobalVersion = true
    )

    assert(
      result
        .map(_.versions)
        .forall(_ == Some("2.0.0" -> "2.1.0-SNAPSHOT"))
    )
  }

  private def project(name: String): ProjectReleaseInfo =
    MonorepoTestSupport.dummyProject(name)
}
