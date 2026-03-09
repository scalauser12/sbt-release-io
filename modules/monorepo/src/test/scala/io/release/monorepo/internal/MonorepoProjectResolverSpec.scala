package io.release.monorepo.internal

import io.release.internal.ExecutionFlags
import io.release.monorepo.ProjectReleaseInfo
import org.specs2.mutable.Specification
import sbt.ProjectRef

import java.io.File
import java.net.URI

class MonorepoProjectResolverSpec extends Specification {

  "MonorepoProjectResolver.applyVersionOverrides" should {

    "apply per-project overrides in non-global mode" in {
      val plan    = MonorepoReleasePlan(
        flags = ExecutionFlags(false, false, false, false, false),
        selectionMode = SelectionMode.ExplicitSelection,
        selectedNames = Seq("core"),
        releaseVersionOverrides = Map("core" -> "1.0.0"),
        nextVersionOverrides = Map("core" -> "1.1.0-SNAPSHOT"),
        globalReleaseVersion = None,
        globalNextVersion = None
      )
      val result  = MonorepoProjectResolver.applyVersionOverrides(
        Seq(project("core"), project("api")),
        plan,
        useGlobalVersion = false
      )
      val core    = result.find(_.name == "core").get
      val api     = result.find(_.name == "api").get

      (core.versions must beSome("1.0.0" -> "1.1.0-SNAPSHOT")) and
        (api.versions must beNone)
    }

    "apply global overrides to every project in global mode" in {
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

      result.map(_.versions) must contain(
        beSome("2.0.0" -> "2.1.0-SNAPSHOT")
      ).forall
    }
  }

  private def project(name: String): ProjectReleaseInfo =
    ProjectReleaseInfo(
      ref = ProjectRef(new URI("file:///tmp/test"), name),
      name = name,
      baseDir = new File(s"/tmp/test/$name"),
      versionFile = new File(s"/tmp/test/$name/version.sbt")
    )
}
