package io.release.monorepo.steps

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseIO.*
import io.release.TestAssertions.assertFailure
import io.release.internal.SbtRuntime
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoReleaseIO
import io.release.monorepo.MonorepoSpecSupport
import io.release.monorepo.SelectionMode
import io.release.monorepo.steps.MonorepoVersionStepsSpec.VersionFixture
import munit.CatsEffectSuite

import java.io.File

class MonorepoVersionStepsSpec extends CatsEffectSuite {

  test("inquireVersions.validate - fail when the resolved version file does not exist") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-missing-file") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject("core", coreBase)
        )
      }
      .use { fixture =>
        val ctx     = fixture.context(Seq("core"))
        val project = fixture.projectInfo("core")

        assertFailure[IllegalStateException, Unit](
          MonorepoVersionSteps.inquireVersions.validate(ctx, project)
        ) { err =>
          assert(err.getMessage.contains("Version file not found for core"))
          assert(err.getMessage.contains(project.versionFile.getPath))
        }
      }
  }

  test("inquireVersions.execute - resolve per-project release and next versions") {
    fixtureResource.use { fixture =>
      val ctx     = MonorepoSpecSupport.withPlan(
        fixture.context(Seq("core")),
        MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.ExplicitSelection)
      )
      val project = fixture.projectInfo("core")

      MonorepoVersionSteps.inquireVersions.execute(ctx, project).map { result =>
        val updated = MonorepoSpecSupport.projectNamed(result.projects, "core")

        assertEquals(updated.versions, Some("0.1.0" -> "0.2.0-SNAPSHOT"))
        assertEquals(updated.versionFile, fixture.versionFile)
      }
    }
  }

  test("setReleaseVersions.execute - write the resolved per-project version file") {
    fixtureResource.use { fixture =>
      val ctx     = fixture.context(
        Seq("core"),
        versionsById = Map("core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"))
      )
      val project = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

      MonorepoVersionSteps.setReleaseVersions.execute(ctx, project) *> IO.blocking {
        val contents = sbt.IO.read(fixture.versionFile)
        assertEquals(contents, """version := "1.0.0"""" + "\n")
      }
    }
  }

  test(
    "setReleaseVersions.execute - fail when a late-bound resolver mutates version files to a shared path"
  ) {
    // Start with distinct per-project version files (the normal setup), then mutate the
    // resolver to a shared file via appendWithSession before executing the write step.
    // This pins down the late-bound scenario: the guard must read the current state at
    // write time, not a cached or pre-mutation snapshot.
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-late-shared") { dir =>
        val coreBase = new File(dir, "core")
        val apiBase  = new File(dir, "api")
        coreBase.mkdirs()
        apiBase.mkdirs()
        sbt.IO.write(new File(coreBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
        sbt.IO.write(new File(apiBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core", "api")),
          MonorepoSpecSupport.versionedProject("core", coreBase),
          MonorepoSpecSupport.versionedProject("api", apiBase)
        )
      }
      .use { fixture =>
        val sharedFile   = new File(fixture.dir, "version.sbt")
        sbt.IO.write(sharedFile, """version := "0.1.0-SNAPSHOT"""" + "\n")
        val mutatedState = SbtRuntime.appendWithSession(
          fixture.state,
          Seq(
            MonorepoReleaseIO.releaseIOMonorepoVersionFile := { (_: sbt.ProjectRef, _: sbt.State) =>
              sharedFile
            }
          )
        )
        val ctx          = MonorepoContext(
          state = mutatedState,
          projects = Seq(
            fixture.projectInfo("core", versions = Some("1.0.0" -> "1.1.0-SNAPSHOT"))
          )
        )
        val project      = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

        assertFailure[IllegalStateException, MonorepoContext](
          MonorepoVersionSteps.setReleaseVersions.execute(ctx, project)
        ) { err =>
          assert(
            err.getMessage.contains("Multiple projects resolve to the same version file"),
            s"Expected shared-file error but got: ${err.getMessage}"
          )
          assert(err.getMessage.contains("core"))
          assert(err.getMessage.contains("api"))
        }
      }
  }

  test("setNextVersions.execute - write the next snapshot to the per-project version file") {
    fixtureResource.use { fixture =>
      val ctx     = fixture.context(
        Seq("core"),
        versionsById = Map("core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"))
      )
      val project = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

      MonorepoVersionSteps.setNextVersions.execute(ctx, project) *> IO.blocking {
        val contents = sbt.IO.read(fixture.versionFile)
        assertEquals(contents, """version := "1.1.0-SNAPSHOT"""" + "\n")
      }
    }
  }

  private val fixtureResource: Resource[IO, VersionFixture] =
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-steps") { dir =>
        val coreBase    = new File(dir, "core")
        coreBase.mkdirs()
        val versionFile = new File(coreBase, "version.sbt")
        sbt.IO.write(versionFile, """version := "0.1.0-SNAPSHOT"""" + "\n")

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              releaseIOVersion     := ((version: String) => version.stripSuffix("-SNAPSHOT")),
              releaseIONextVersion := ((_: String) => "0.2.0-SNAPSHOT")
            )
          )
        )
      }
      .map { fixture =>
        VersionFixture(
          loaded = fixture,
          versionFile = new File(new File(fixture.dir, "core"), "version.sbt")
        )
      }
}

private object MonorepoVersionStepsSpec {
  final case class VersionFixture(
      loaded: MonorepoSpecSupport.LoadedFixture,
      versionFile: File
  ) {
    def context(
        selectedProjectIds: Seq[String],
        versionsById: Map[String, (String, String)] = Map.empty
    ): MonorepoContext =
      loaded.context(selectedProjectIds, versionsById = versionsById)

    def projectInfo(id: String) = loaded.projectInfo(id)
  }
}
