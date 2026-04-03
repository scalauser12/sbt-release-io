package io.release.monorepo.steps

import io.release.ReleaseIO
import io.release.TestAssertions.assertFailure
import munit.CatsEffectSuite
import sbt.*

@scala.annotation.nowarn("cat=deprecation")
class MonorepoPublishFlowSpec extends CatsEffectSuite with MonorepoPublishStepsSpecSupport {

  test(
    "checkSnapshotDependencies.validate - fail on snapshot dependencies in non-interactive mode"
  ) {
    singleProjectFixtureResource("monorepo-publish-snapshots") { _ =>
      Seq(
        ReleaseIO.releaseIODiagnosticsSnapshotDependencies := Seq(
          "org.example" % "dep" % "1.0.0-SNAPSHOT"
        )
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      assertFailure[IllegalStateException, Unit](
        MonorepoPublishSteps.checkSnapshotDependencies.validate(ctx, project)
      ) { err =>
        assert(err.getMessage.contains("Snapshot dependencies found in core"))
        assert(err.getMessage.contains("org.example:dep:1.0.0-SNAPSHOT"))
      }
    }
  }

  test("checkSnapshotDependencies.validate function value - fail on snapshot dependencies") {
    singleProjectFixtureResource("monorepo-publish-snapshots-field") { _ =>
      Seq(
        ReleaseIO.releaseIODiagnosticsSnapshotDependencies := Seq(
          "org.example" % "dep" % "1.0.0-SNAPSHOT"
        )
      )
    }.use { fixture =>
      val ctx      = fixture.context(Seq("core"))
      val project  = fixture.projectInfo("core")
      val validate = MonorepoPublishSteps.checkSnapshotDependencies.validate

      assertFailure[IllegalStateException, Unit](validate(ctx, project)) { err =>
        assert(err.getMessage.contains("Snapshot dependencies found in core"))
        assert(err.getMessage.contains("org.example:dep:1.0.0-SNAPSHOT"))
      }
    }
  }

  test("runTests.execute - skip project tests when skipTests is enabled") {
    singleProjectFixtureResource("monorepo-publish-skip-tests") { projectBase =>
      Seq(
        MonorepoStepTestCompat.successfulTestTaskSetting(
          new java.io.File(projectBase, "test-ran.txt")
        )
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"), skipTests = true)
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.runTests.execute(ctx, project).map { result =>
        assertEquals(result.skipTests, true)
        assert(!new java.io.File(fixture.dir, "core/test-ran.txt").exists())
      }
    }
  }

  test("runTests.execute - run the configured project test task when skipTests is disabled") {
    singleProjectFixtureResource("monorepo-publish-run-tests") { projectBase =>
      Seq(
        MonorepoStepTestCompat.successfulTestTaskSetting(
          new java.io.File(projectBase, "test-ran.txt")
        )
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.runTests.execute(ctx, project).map { _ =>
        assert(new java.io.File(fixture.dir, "core/test-ran.txt").exists())
      }
    }
  }

  test("checkSnapshotDependencies.validate - pass when no snapshot dependencies") {
    singleProjectFixtureResource("monorepo-publish-no-snapshots") { _ =>
      Seq(ReleaseIO.releaseIODiagnosticsSnapshotDependencies := Seq.empty[ModuleID])
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.checkSnapshotDependencies.validate(ctx, project)
    }
  }

  test("checkSnapshotDependencies.execute - return context unchanged") {
    singleProjectFixtureResource("monorepo-publish-snap-exec")().use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.checkSnapshotDependencies.execute(ctx, project).map { result =>
        assert(result eq ctx)
      }
    }
  }

  test("runClean.execute - succeed when clean reports no failure") {
    singleProjectFixtureResource("monorepo-publish-clean-ok")().use { fixture =>
      val ctx = fixture.context(Seq("core"))

      _root_.io.release.monorepo.MonorepoProcessStep
        .compose(Seq(MonorepoPublishSteps.runClean))(ctx)
        .map { result =>
          assert(!result.failed)
        }
    }
  }
}
