package io.release.monorepo.internal

import io.release.internal.ExecutionFlags
import io.release.monorepo.MonorepoTagStrategy
import org.specs2.mutable.Specification
import sbt.ProjectRef

import java.io.File
import java.net.URI

import cats.effect.unsafe.implicits.global

class MonorepoReleasePlannerSpec extends Specification {

  "MonorepoReleasePlanner.validateOverrideInputs" should {

    "reject duplicate per-project release-version overrides" in {
      val result = MonorepoReleasePlanner.validateOverrideInputs(
        baseInputs.copy(releaseVersionPairs = Seq("core" -> "1.0.0", "core" -> "1.1.0")),
        useGlobalVersion = false
      )

      result must beLeft.like { case message =>
        message must contain("Duplicate per-project release-version overrides")
      }
    }

    "reject explicit project selection combined with all-changed" in {
      val result = MonorepoReleasePlanner.validateOverrideInputs(
        baseInputs.copy(allChanged = true, selectedNames = Seq("core")),
        useGlobalVersion = false
      )

      result must beLeft.like { case message =>
        message must contain("Cannot combine 'all-changed' with explicit project selection")
      }
    }

    "derive the explicit selection mode from validated inputs" in {
      val result = MonorepoReleasePlanner.validateOverrideInputs(
        baseInputs.copy(selectedNames = Seq("core")),
        useGlobalVersion = false
      )

      result must beRight.like { case validated =>
        validated.selectionMode must_== SelectionMode.ExplicitSelection
      }
    }
  }

  "MonorepoReleasePlanner.validateResolvedProjects" should {

    "reject subset selection when global version mode is enabled" in {
      val validated = validatedInputs(
        baseInputs.copy(selectedNames = Seq("core")),
        useGlobalVersion = true
      )

      MonorepoReleasePlanner.validateResolvedProjects(allProjects, validated) must beLeft.like {
        case message =>
          message must contain("Global version mode is active")
      }
    }
  }

  "MonorepoReleasePlanner.enforceGlobalVersionAllOrNothing" should {

    "fail when change detection keeps only a subset in global version mode" in {
      val result =
        MonorepoReleasePlanner
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
      MonorepoReleasePlanner
        .enforceGlobalVersionAllOrNothing(
          allProjects = allProjects,
          changedProjects = allProjects.take(1),
          useGlobalVersion = false
        )
        .unsafeRunSync() must_== allProjects.take(1)
    }
  }

  private val baseInputs = MonorepoReleasePlanner.Inputs(
    flags = ExecutionFlags(
      useDefaults = false,
      skipTests = false,
      skipPublish = false,
      interactive = false,
      crossBuild = false
    ),
    allChanged = false,
    tagStrategy = MonorepoTagStrategy.PerProject,
    selectedNames = Nil,
    releaseVersionPairs = Nil,
    nextVersionPairs = Nil,
    globalReleaseVersions = Nil,
    globalNextVersions = Nil
  )

  private val allProjects = Seq(
    projectPlan("core"),
    projectPlan("api")
  )

  private def validatedInputs(
      inputs: MonorepoReleasePlanner.Inputs,
      useGlobalVersion: Boolean
  ): MonorepoReleasePlanner.ValidatedInputs =
    MonorepoReleasePlanner.validateOverrideInputs(inputs, useGlobalVersion) match {
      case Right(validated) => validated
      case Left(message)    => throw new RuntimeException(s"Expected valid inputs but got: $message")
    }

  private def projectPlan(name: String): ProjectPlan =
    ProjectPlan(
      ref = ProjectRef(new URI("file:///tmp/test"), name),
      name = name,
      baseDir = new File(s"/tmp/test/$name"),
      version = ProjectVersionPlan(
        versionFile = new File(s"/tmp/test/$name/version.sbt"),
        releaseVersionOverride = None,
        nextVersionOverride = None
      )
    )
}
