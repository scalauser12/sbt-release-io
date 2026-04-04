package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.ClasspathDependency
import sbt.LocalProject
import sbt.ProjectRef
import sbt.classpathDependency

import java.io.File

class MonorepoSelectionResolverSpec extends CatsEffectSuite {

  test("resolve - preserve live project order for explicit selection") {
    resolverFixtureResource("monorepo-selection-explicit").use { fixture =>
      val ctx  = fixture.context(Seq("consumer", "api"))
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        selectedNames = Seq("consumer", "api")
      )

      MonorepoSelectionResolver.resolve(ctx, plan).map { result =>
        assertEquals(result.selectionMode, SelectionMode.ExplicitSelection)
        assertEquals(result.projects.map(_.name), Seq("api", "consumer"))
      }
    }
  }

  test("resolve - return all projects with AllChanged when detectChanges is disabled") {
    resolverFixtureResource(
      prefix = "monorepo-selection-all-changed",
      rootSettings = Seq(
        MonorepoReleaseIO.releaseIOMonorepoDetectionEnabled := false
      )
    ).use { fixture =>
      val ctx = fixture.context(Seq("core", "api", "consumer"))

      MonorepoSelectionResolver
        .resolve(ctx, MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.DetectChanges))
        .map { result =>
          assertEquals(result.selectionMode, SelectionMode.AllChanged)
          assertEquals(result.projects.map(_.name), Seq("core", "api", "consumer"))
        }
    }
  }

  test("resolve - use the custom change detector without downstream expansion") {
    resolverFixtureResource(
      prefix = "monorepo-selection-custom",
      rootSettings = Seq(
        MonorepoReleaseIO.releaseIOMonorepoDetectionChangeDetector    :=
          Some((ref: ProjectRef, _: File, _: sbt.State) => IO.pure(ref.project == "api")),
        MonorepoReleaseIO.releaseIOMonorepoDetectionIncludeDownstream := false
      )
    ).use { fixture =>
      MonorepoSelectionResolver
        .resolve(
          fixture.context(Seq("core", "api", "consumer")),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.DetectChanges)
        )
        .map { result =>
          assertEquals(result.selectionMode, SelectionMode.DetectChanges)
          assertEquals(result.projects.map(_.name), Seq("api"))
        }
    }
  }

  test("resolve - expand detected changes to downstream dependents when requested") {
    resolverFixtureResource(
      prefix = "monorepo-selection-downstream",
      rootSettings = Seq(
        MonorepoReleaseIO.releaseIOMonorepoDetectionChangeDetector    :=
          Some((ref: ProjectRef, _: File, _: sbt.State) => IO.pure(ref.project == "api")),
        MonorepoReleaseIO.releaseIOMonorepoDetectionIncludeDownstream := true
      )
    ).use { fixture =>
      MonorepoSelectionResolver
        .resolve(
          fixture.context(Seq("core", "api", "consumer")),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.DetectChanges)
        )
        .map { result =>
          assertEquals(result.selectionMode, SelectionMode.DetectChanges)
          assertEquals(result.projects.map(_.name), Seq("api", "consumer"))
        }
    }
  }

  test("resolve - force-include unchanged projects that have CLI version overrides") {
    resolverFixtureResource(
      prefix = "monorepo-selection-force-include",
      rootSettings = Seq(
        MonorepoReleaseIO.releaseIOMonorepoDetectionChangeDetector :=
          Some((ref: ProjectRef, _: File, _: sbt.State) => IO.pure(ref.project == "core"))
      )
    ).use { fixture =>
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.DetectChanges,
        releaseVersionOverrides = Map("api" -> "3.0.0"),
        nextVersionOverrides = Map("api" -> "3.1.0-SNAPSHOT")
      )

      MonorepoSelectionResolver.resolve(fixture.context(Seq.empty), plan).map { result =>
        assertEquals(result.projects.map(_.name), Seq("core", "api"))
        assertEquals(
          result.projects.find(_.name == "api").flatMap(_.versions),
          Some("3.0.0" -> "3.1.0-SNAPSHOT")
        )
      }
    }
  }

  test("resolve - reject version overrides that target projects not selected for release") {
    resolverFixtureResource("monorepo-selection-unused-overrides").use { fixture =>
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        selectedNames = Seq("core"),
        releaseVersionOverrides = Map("api" -> "3.0.0")
      )

      assertFailure[IllegalStateException, MonorepoSelectionResolver.SelectionResult](
        MonorepoSelectionResolver.resolve(fixture.context(Seq.empty), plan)
      )(err =>
        assert(
          err.getMessage.contains("Version overrides target projects not selected for release: api")
        )
      )
    }
  }

  test("resolve - reject unknown selected project names against the live build") {
    resolverFixtureResource("monorepo-selection-unknown-selection").use { fixture =>
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        selectedNames = Seq("missing")
      )

      assertFailure[IllegalStateException, MonorepoSelectionResolver.SelectionResult](
        MonorepoSelectionResolver.resolve(fixture.context(Seq.empty), plan)
      )(err => assert(err.getMessage.contains("Unknown projects: missing")))
    }
  }

  test("resolve - reject unknown override project names against the live build") {
    resolverFixtureResource("monorepo-selection-unknown-overrides").use { fixture =>
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.DetectChanges,
        releaseVersionOverrides = Map("missing" -> "1.0.0")
      )

      assertFailure[IllegalStateException, MonorepoSelectionResolver.SelectionResult](
        MonorepoSelectionResolver.resolve(fixture.context(Seq.empty), plan)
      )(err => assert(err.getMessage.contains("Unknown projects in version overrides: missing")))
    }
  }

  test("validateResolvedProjects - reject duplicate live project names before selection") {
    Resource
      .both(
        TestSupport.tempDirResource("monorepo-selection-duplicate-a"),
        TestSupport.tempDirResource("monorepo-selection-duplicate-b")
      )
      .use { case (dirA, dirB) =>
        IO {
          val duplicateA = ProjectReleaseInfo(
            ref = ProjectRef(dirA.toURI, "shared"),
            name = "shared",
            baseDir = dirA,
            versionFile = new File(dirA, "version.sbt")
          )
          val duplicateB = ProjectReleaseInfo(
            ref = ProjectRef(dirB.toURI, "shared"),
            name = "shared",
            baseDir = dirB,
            versionFile = new File(dirB, "version.sbt")
          )

          MonorepoSelectionResolver
            .validateResolvedProjects(
              Seq(duplicateA, duplicateB),
              MonorepoSpecSupport.releasePlan()
            ) match {
            case Left(message) =>
              assert(message.contains("Duplicate configured monorepo project ids"))
              assert(message.contains("shared"))
              assert(message.contains(dirA.getAbsolutePath))
              assert(message.contains(dirB.getAbsolutePath))
              assert(message.contains("releaseIOMonorepoSelectionProjects"))
            case Right(value)  =>
              fail(s"Expected duplicate project-name validation to fail but got: $value")
          }
        }
      }
  }

  private def resolverFixtureResource(
      prefix: String,
      rootSettings: Seq[sbt.Def.Setting[?]] = Nil
  ): Resource[IO, MonorepoSpecSupport.LoadedFixture] =
    MonorepoSpecSupport.loadedFixtureResource(prefix) { dir =>
      val coreBase     = new File(dir, "core")
      val apiBase      = new File(dir, "api")
      val consumerBase = new File(dir, "consumer")
      coreBase.mkdirs()
      apiBase.mkdirs()
      consumerBase.mkdirs()
      sbt.IO.write(
        new File(dir, "version.sbt"),
        """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n"
      )
      sbt.IO.write(new File(coreBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
      sbt.IO.write(new File(apiBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
      sbt.IO.write(new File(consumerBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")

      Seq(
        MonorepoSpecSupport.monorepoRootProject(
          dir,
          projectIds = Seq("core", "api", "consumer"),
          settings = rootSettings
        ),
        MonorepoSpecSupport.versionedProject("core", coreBase),
        MonorepoSpecSupport.versionedProject("api", apiBase),
        MonorepoSpecSupport
          .versionedProject("consumer", consumerBase)
          .dependsOn(projectDependency("api"))
      )
    }

  private def projectDependency(id: String): ClasspathDependency =
    classpathDependency(LocalProject(id))
}
