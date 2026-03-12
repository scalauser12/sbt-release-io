package io.release.monorepo.internal

import io.release.internal.ExecutionFlags
import io.release.monorepo.ProjectReleaseInfo
import org.specs2.mutable.Specification
import sbt.ProjectRef

import java.io.File
import java.net.URI

import cats.effect.unsafe.implicits.global

class MonorepoReleasePlanSpec extends Specification {

  "MonorepoReleasePlan.validateOverrideInputs" should {

    "reject duplicate per-project release-version overrides" in {
      val result = MonorepoReleasePlan.validateOverrideInputs(
        baseInputs.copy(releaseVersionPairs = Seq("core" -> "1.0.0", "core" -> "1.1.0"))
      )

      result must beLeft.like { case message =>
        message must contain("Duplicate per-project release-version overrides")
      }
    }

    "reject explicit project selection combined with all-changed" in {
      val result = MonorepoReleasePlan.validateOverrideInputs(
        baseInputs.copy(allChanged = true, selectedNames = Seq("core"))
      )

      result must beLeft.like { case message =>
        message must contain("Cannot combine 'all-changed' with explicit project selection")
      }
    }

    "derive the explicit selection mode from validated inputs" in {
      val result = MonorepoReleasePlan.validateOverrideInputs(
        baseInputs.copy(selectedNames = Seq("core"))
      )

      result must beRight.like { case validated =>
        validated.selectionMode must_== SelectionMode.ExplicitSelection
      }
    }

    "allow global overrides at startup before runtime global-version validation" in {
      val result = MonorepoReleasePlan.validateOverrideInputs(
        baseInputs.copy(globalReleaseVersions = Seq("1.0.0"))
      )

      result must beRight.like { case validated =>
        validated.globalReleaseVersion must beSome("1.0.0")
      }
    }
  }

  "MonorepoReleasePlan.enforceGlobalVersionAllOrNothing" should {

    "fail when change detection keeps only a subset in global version mode" in {
      val result =
        MonorepoReleasePlan
          .enforceGlobalVersionAllOrNothing(
            allProjects = allProjects,
            changedProjects = allProjects.take(1),
            useGlobalVersion = true
          )
          .attempt
          .unsafeRunSync()

      result must beLeft.like { case err: IllegalStateException =>
        err.getMessage must contain("Global version mode is active")
      }
    }

    "allow unchanged selection rules outside global version mode" in {
      MonorepoReleasePlan
        .enforceGlobalVersionAllOrNothing(
          allProjects = allProjects,
          changedProjects = allProjects.take(1),
          useGlobalVersion = false
        )
        .unsafeRunSync() must_== allProjects.take(1)
    }
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
    ProjectReleaseInfo(
      ref = ProjectRef(new URI("file:///tmp/test"), name),
      name = name,
      baseDir = new File(s"/tmp/test/$name"),
      versionFile = new File(s"/tmp/test/$name/version.sbt")
    )
}
