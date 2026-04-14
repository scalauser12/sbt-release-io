package io.release.monorepo.internal.steps

import io.release.ReleaseSharedKeys
import io.release.TestAssertions.assertFailure
import io.release.monorepo.MonorepoSpecSupport
import io.release.monorepo.internal.*
import io.release.monorepo.internal.MonorepoComposer
import io.release.monorepo.internal.steps.*
import io.release.runtime.sbt.SbtCompat
import munit.CatsEffectSuite
import sbt.*

import java.io.File

class MonorepoPublishFlowSpec extends CatsEffectSuite with MonorepoPublishStepsSpecSupport {

  test(
    "checkSnapshotDependencies.validate - fail on snapshot dependencies in non-interactive mode"
  ) {
    singleProjectFixtureResource("monorepo-publish-snapshots") { _ =>
      Seq(
        ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies := Seq(
          "org.example" % "dep" % "1.0.0-SNAPSHOT"
        )
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      assertFailure[IllegalStateException, Unit](
        MonorepoPublishSteps.checkSnapshotDependencies.validate(ctx, project).void
      ) { err =>
        assert(err.getMessage.contains("Snapshot dependencies found in core"))
        assert(err.getMessage.contains("org.example:dep:1.0.0-SNAPSHOT"))
      }
    }
  }

  test("checkSnapshotDependencies.validate function value - fail on snapshot dependencies") {
    singleProjectFixtureResource("monorepo-publish-snapshots-field") { _ =>
      Seq(
        ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies := Seq(
          "org.example" % "dep" % "1.0.0-SNAPSHOT"
        )
      )
    }.use { fixture =>
      val ctx      = fixture.context(Seq("core"))
      val project  = fixture.projectInfo("core")
      val validate = MonorepoPublishSteps.checkSnapshotDependencies.validate

      assertFailure[IllegalStateException, Unit](validate(ctx, project).void) { err =>
        assert(err.getMessage.contains("Snapshot dependencies found in core"))
        assert(err.getMessage.contains("org.example:dep:1.0.0-SNAPSHOT"))
      }
    }
  }

  test("checkSnapshotDependencies.validate - fail when diagnostics task reports FailureCommand") {
    singleProjectFixtureResource("monorepo-publish-snapshots-failure-command") { projectBase =>
      Seq(
        MonorepoStepTestCompat.failureCommandSnapshotDependenciesTaskSetting(
          new java.io.File(projectBase, "snapshot-deps-ran.txt")
        )
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")
      val marker  = new java.io.File(fixture.dir, "core/snapshot-deps-ran.txt")

      assertFailure[IllegalStateException, Unit](
        MonorepoPublishSteps.checkSnapshotDependencies.validate(ctx, project).void
      ) { err =>
        assert(marker.exists())
        assert(err.getMessage.contains("core"))
        assert(err.getCause != null)
        assert(err.getCause.getMessage.contains("FailureCommand"))
        assert(
          err.getCause.getMessage.contains(
            ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies.key.label
          )
        )
      }
    }
  }

  test(
    "checkSnapshotDependencies.validate - fall back to managedClasspath when diagnostics task is undefined"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-managed-classpath-fallback") { dir =>
        val projectBase = new File(dir, "core")
        projectBase.mkdirs()
        val marker      = new File(projectBase, "managed-classpath-ran.txt")

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core")
          ),
          MonorepoSpecSupport
            .versionedProject(
              "core",
              projectBase,
              settings = Seq(MonorepoStepTestCompat.managedClasspathSetting(marker))
            )
            .enablePlugins(sbt.plugins.JvmPlugin)
        )
      }
      .use { fixture =>
        val ctx     = fixture.context(Seq("core"))
        val project = fixture.projectInfo("core")

        MonorepoPublishSteps.checkSnapshotDependencies.validate(ctx, project).map { result =>
          assertEquals(result.failed, false)
          assert(new File(fixture.dir, "core/managed-classpath-ran.txt").exists())
        }
      }
  }

  test(
    "checkSnapshotDependencies.validate - fail when managedClasspath fallback reports FailureCommand"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-managed-classpath-failure-command") { dir =>
        val projectBase = new File(dir, "core")
        projectBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core")
          ),
          MonorepoSpecSupport
            .versionedProject(
              "core",
              projectBase,
              settings = Seq(
                MonorepoStepTestCompat.managedClasspathSetting(
                  new File(projectBase, "managed-classpath-ran.txt")
                )
              )
            )
            .enablePlugins(sbt.plugins.JvmPlugin)
        )
      }
      .use { fixture =>
        val baseCtx = fixture.context(Seq("core"))
        val ctx     = baseCtx.withState(
          baseCtx.state.copy(
            remainingCommands = SbtCompat.FailureCommand :: baseCtx.state.remainingCommands
          )
        )
        val project = fixture.projectInfo("core")

        assertFailure[IllegalStateException, Unit](
          MonorepoPublishSteps.checkSnapshotDependencies.validate(ctx, project).void
        ) { err =>
          assert(err.getMessage.contains("FailureCommand"))
          assert(err.getMessage.contains(Keys.managedClasspath.key.label))
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
      Seq(
        ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies := Seq
          .empty[ModuleID]
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.checkSnapshotDependencies.validate(ctx, project).void
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

      MonorepoComposer
        .compose(Seq(MonorepoPublishSteps.runClean))(ctx)
        .map { result =>
          assert(!result.failed)
        }
    }
  }
}
