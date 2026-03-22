package io.release.monorepo

import io.release.internal.ExecutionFlags
import munit.FunSuite

class MonorepoProjectResolverSpec extends FunSuite {

  test("mergeSnapshot - carry forward release state by matching project refs") {
    val failure  = new RuntimeException("previous failure")
    val current  = Seq(
      project("core").copy(
        versions = Some("1.0.0" -> "1.1.0-SNAPSHOT"),
        tagName = Some("core-v1.0.0"),
        failed = true,
        failureCause = Some(failure)
      ),
      project("legacy").copy(
        versions = Some("0.1.0" -> "0.2.0-SNAPSHOT"),
        tagName = Some("legacy-v0.1.0"),
        failed = true,
        failureCause = Some(new RuntimeException("legacy failure"))
      )
    )
    val resolved = Seq(
      project("core"),
      project("api").copy(versions = Some("0.4.0" -> "0.5.0-SNAPSHOT"))
    )
    val result   = MonorepoProjectResolver.mergeSnapshot(current, resolved)
    val core     = projectNamed(result, "core")
    val api      = projectNamed(result, "api")

    assertEquals(core.baseDir, resolved.head.baseDir)
    assertEquals(core.versionFile, resolved.head.versionFile)
    assertEquals(core.versions, Some("1.0.0" -> "1.1.0-SNAPSHOT"))
    assertEquals(core.tagName, Some("core-v1.0.0"))
    assertEquals(core.failed, true)
    assertEquals(core.failureCause, Some(failure))
    assertEquals(api.versions, Some("0.4.0" -> "0.5.0-SNAPSHOT"))
    assertEquals(api.tagName, None)
    assertEquals(api.failed, false)
    assertEquals(result.map(_.name), Seq("core", "api"))
  }

  test("applyVersionOverrides - return empty output for empty projects") {
    val result = MonorepoProjectResolver.applyVersionOverrides(
      Seq.empty,
      plan(selectionMode = SelectionMode.DetectChanges),
      useGlobalVersion = false
    )

    assertEquals(result, Seq.empty)
  }

  test("applyVersionOverrides - apply per-project overrides in non-global mode") {
    val plan   = MonorepoReleasePlan(
      flags = defaultFlags,
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

  test(
    "applyVersionOverrides - preserve the current next version when only release is overridden"
  ) {
    val result = MonorepoProjectResolver.applyVersionOverrides(
      Seq(
        project("core", versions = Some("0.9.0" -> "1.0.0-SNAPSHOT")),
        project("api", versions = Some("0.4.0" -> "0.5.0-SNAPSHOT"))
      ),
      plan(
        selectionMode = SelectionMode.ExplicitSelection,
        releaseVersionOverrides = Map("core" -> "1.0.0")
      ),
      useGlobalVersion = false
    )

    assertEquals(projectNamed(result, "core").versions, Some("1.0.0" -> "1.0.0-SNAPSHOT"))
    assertEquals(projectNamed(result, "api").versions, Some("0.4.0" -> "0.5.0-SNAPSHOT"))
  }

  test(
    "applyVersionOverrides - preserve the current release version when only next is overridden"
  ) {
    val result = MonorepoProjectResolver.applyVersionOverrides(
      Seq(
        project("core", versions = Some("1.0.0" -> "1.1.0-SNAPSHOT")),
        project("api", versions = Some("0.4.0" -> "0.5.0-SNAPSHOT"))
      ),
      plan(
        selectionMode = SelectionMode.ExplicitSelection,
        nextVersionOverrides = Map("core" -> "1.2.0-SNAPSHOT")
      ),
      useGlobalVersion = false
    )

    assertEquals(projectNamed(result, "core").versions, Some("1.0.0" -> "1.2.0-SNAPSHOT"))
    assertEquals(projectNamed(result, "api").versions, Some("0.4.0" -> "0.5.0-SNAPSHOT"))
  }

  test("applyVersionOverrides - apply global overrides to every project in global mode") {
    val plan   = MonorepoReleasePlan(
      flags = defaultFlags,
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

  test(
    "applyVersionOverrides - preserve the current next version for global release-only overrides"
  ) {
    val result = MonorepoProjectResolver.applyVersionOverrides(
      Seq(
        project("core", versions = Some("0.9.0" -> "1.0.0-SNAPSHOT")),
        project("api", versions = Some("0.4.0" -> "0.5.0-SNAPSHOT"))
      ),
      plan(
        selectionMode = SelectionMode.AllChanged,
        globalReleaseVersion = Some("2.0.0")
      ),
      useGlobalVersion = true
    )

    assertEquals(projectNamed(result, "core").versions, Some("2.0.0" -> "1.0.0-SNAPSHOT"))
    assertEquals(projectNamed(result, "api").versions, Some("2.0.0" -> "0.5.0-SNAPSHOT"))
  }

  test(
    "applyVersionOverrides - preserve the current release version for global next-only overrides"
  ) {
    val result = MonorepoProjectResolver.applyVersionOverrides(
      Seq(
        project("core", versions = Some("1.0.0" -> "1.1.0-SNAPSHOT")),
        project("api", versions = Some("0.4.0" -> "0.5.0-SNAPSHOT"))
      ),
      plan(
        selectionMode = SelectionMode.AllChanged,
        globalNextVersion = Some("2.1.0-SNAPSHOT")
      ),
      useGlobalVersion = true
    )

    assertEquals(projectNamed(result, "core").versions, Some("1.0.0" -> "2.1.0-SNAPSHOT"))
    assertEquals(projectNamed(result, "api").versions, Some("0.4.0" -> "2.1.0-SNAPSHOT"))
  }

  private val defaultFlags = ExecutionFlags(
    useDefaults = false,
    skipTests = false,
    skipPublish = false,
    interactive = false,
    crossBuild = false
  )

  private def plan(
      selectionMode: SelectionMode,
      selectedNames: Seq[String] = Nil,
      releaseVersionOverrides: Map[String, String] = Map.empty,
      nextVersionOverrides: Map[String, String] = Map.empty,
      globalReleaseVersion: Option[String] = None,
      globalNextVersion: Option[String] = None
  ): MonorepoReleasePlan =
    MonorepoReleasePlan(
      flags = defaultFlags,
      selectionMode = selectionMode,
      selectedNames = selectedNames,
      releaseVersionOverrides = releaseVersionOverrides,
      nextVersionOverrides = nextVersionOverrides,
      globalReleaseVersion = globalReleaseVersion,
      globalNextVersion = globalNextVersion
    )

  private def project(
      name: String,
      versions: Option[(String, String)] = None
  ): ProjectReleaseInfo =
    MonorepoTestSupport.dummyProject(name).copy(versions = versions)

  private def projectNamed(
      projects: Seq[ProjectReleaseInfo],
      name: String
  ): ProjectReleaseInfo =
    projects.find(_.name == name).getOrElse(fail(s"Expected project '$name'"))
}
