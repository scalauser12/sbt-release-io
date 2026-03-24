package io.release.monorepo.steps

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseIO
import io.release.TestAssertions.assertFailure
import io.release.internal.PublishValidation
import io.release.monorepo.MonorepoProjectFailures
import io.release.monorepo.MonorepoReleaseIO
import io.release.monorepo.MonorepoSpecSupport
import io.release.monorepo.MonorepoStepIO
import munit.CatsEffectSuite
import sbt.*

import java.io.File

class MonorepoPublishStepsSpec extends CatsEffectSuite {

  test(
    "checkSnapshotDependencies.validate - fail on snapshot dependencies in non-interactive mode"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-snapshots") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              ReleaseIO.releaseIOSnapshotDependencies := Seq(
                "org.example" % "dep" % "1.0.0-SNAPSHOT"
              )
            )
          )
        )
      }
      .use { fixture =>
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

  test("runTests.execute - skip project tests when skipTests is enabled") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-skip-tests") { dir =>
        val coreBase = new File(dir, "core")
        val marker   = new File(dir, "test-ran.txt")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              MonorepoStepTestCompat.successfulTestTaskSetting(marker)
            )
          )
        )
      }
      .use { fixture =>
        val ctx     = fixture.context(Seq("core"), skipTests = true)
        val project = fixture.projectInfo("core")

        MonorepoPublishSteps.runTests.execute(ctx, project).map { result =>
          assertEquals(result.skipTests, true)
          assert(!new File(fixture.dir, "test-ran.txt").exists())
        }
      }
  }

  test("runTests.execute - run the configured project test task when skipTests is disabled") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-run-tests") { dir =>
        val coreBase = new File(dir, "core")
        val marker   = new File(dir, "test-ran.txt")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              MonorepoStepTestCompat.successfulTestTaskSetting(marker)
            )
          )
        )
      }
      .use { fixture =>
        val ctx     = fixture.context(Seq("core"))
        val project = fixture.projectInfo("core")

        MonorepoPublishSteps.runTests.execute(ctx, project).map { _ =>
          assert(new File(fixture.dir, "test-ran.txt").exists())
        }
      }
  }

  test(
    "runTests via compose - consume FailureCommand per project, keep later projects running, and fail with attribution"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-run-tests-failure-command") { dir =>
        val coreBase = new File(dir, "core")
        val apiBase  = new File(dir, "api")
        val coreRun  = new File(coreBase, "test-ran.txt")
        val apiRun   = new File(apiBase, "test-ran.txt")
        coreBase.mkdirs()
        apiBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core", "api")),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(MonorepoStepTestCompat.failureCommandTestTaskSetting(coreRun))
          ),
          MonorepoSpecSupport.versionedProject(
            "api",
            apiBase,
            settings = Seq(MonorepoStepTestCompat.successfulTestTaskSetting(apiRun))
          )
        )
      }
      .use { fixture =>
        val ctx = fixture.context(Seq("core", "api"))

        MonorepoStepIO.compose(Seq(MonorepoPublishSteps.runTests))(ctx).map { result =>
          val coreRun   = new File(
            MonorepoSpecSupport.projectNamed(result.projects, "core").baseDir,
            "test-ran.txt"
          )
          val apiRun    = new File(
            MonorepoSpecSupport.projectNamed(result.projects, "api").baseDir,
            "test-ran.txt"
          )
          assert(result.failed)
          assert(coreRun.exists())
          assert(apiRun.exists())
          assertEquals(result.state.remainingCommands, Nil)
          val aggregate = requireProjectFailures(result.failureCause)
          assertEquals(aggregate.failures.map(_.projectName), Seq("core"))
          assert(
            aggregate.failures.head.cause.exists(
              _.getMessage.contains("core: sbt task reported failure via FailureCommand")
            )
          )
        }
      }
  }

  test(
    "runClean via compose - consume FailureCommand and fail with project attribution"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-run-clean-failure-command") { dir =>
        val coreBase = new File(dir, "core")
        val coreRun  = new File(coreBase, "clean-ran.txt")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(MonorepoStepTestCompat.failureCommandCleanTaskSetting(coreRun))
          )
        )
      }
      .use { fixture =>
        val ctx = fixture.context(Seq("core"))

        MonorepoStepIO.compose(Seq(MonorepoPublishSteps.runClean))(ctx).map { result =>
          val coreRun   = new File(
            MonorepoSpecSupport.projectNamed(result.projects, "core").baseDir,
            "clean-ran.txt"
          )
          assert(result.failed)
          assert(coreRun.exists())
          assertEquals(result.state.remainingCommands, Nil)
          val aggregate = requireProjectFailures(result.failureCause)
          assertEquals(aggregate.failures.map(_.projectName), Seq("core"))
          assert(
            aggregate.failures.head.cause.exists(
              _.getMessage.contains("core: sbt task reported failure via FailureCommand")
            )
          )
        }
      }
  }

  test("publishArtifacts.validate - fail when checks are enabled and publishTo is empty") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-validate-fail") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core"),
            settings = Seq(
              MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks := true
            )
          ),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              sbt.Keys.publish / sbt.Keys.skip := false,
              sbt.Keys.publishTo               := None
            )
          )
        )
      }
      .use { fixture =>
        val ctx     = fixture.context(Seq("core"))
        val project = fixture.projectInfo("core")

        assertFailure[IllegalStateException, Unit](
          MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
        )(err => assertEquals(err.getMessage, PublishValidation.message("core")))
      }
  }

  test("publishArtifacts.validate - bypass checks when disabled or publish is globally skipped") {
    val checksDisabled =
      MonorepoSpecSupport.loadedFixtureResource("monorepo-publish-validate-disabled") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core"),
            settings = Seq(
              MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks := false
            )
          ),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              sbt.Keys.publish / sbt.Keys.skip := false,
              sbt.Keys.publishTo               := None
            )
          )
        )
      }

    val skipPublish = MonorepoSpecSupport.loadedFixtureResource("monorepo-publish-validate-skip") {
      dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core"),
            settings = Seq(
              MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks := true
            )
          ),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              sbt.Keys.publish / sbt.Keys.skip := false,
              sbt.Keys.publishTo               := None
            )
          )
        )
    }

    Resource.both(checksDisabled, skipPublish).use { case (disabledFixture, skippedFixture) =>
      val disabledCtx     = disabledFixture.context(Seq("core"))
      val disabledProject = disabledFixture.projectInfo("core")
      val skippedCtx      = skippedFixture.context(Seq("core"), skipPublish = true)
      val skippedProject  = skippedFixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.validate(disabledCtx, disabledProject) *>
        MonorepoPublishSteps.publishArtifacts.validate(skippedCtx, skippedProject)
    }
  }

  test("publishArtifacts.execute - skip the publish task when publish / skip is true") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-skip-action") { dir =>
        val coreBase = new File(dir, "core")
        val marker   = new File(dir, "published.txt")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              sbt.Keys.publish / sbt.Keys.skip          := true,
              ReleaseIO.releaseIOPublishArtifactsAction := {
                sbt.IO.write(marker, "published")
              }
            )
          )
        )
      }
      .use { fixture =>
        val ctx     = fixture.context(Seq("core"))
        val project = fixture.projectInfo("core")

        MonorepoPublishSteps.publishArtifacts.execute(ctx, project).map { _ =>
          assert(!new File(fixture.dir, "published.txt").exists())
        }
      }
  }

  test("publishArtifacts.execute - run the configured publish task when publish is enabled") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-run-action") { dir =>
        val coreBase = new File(dir, "core")
        val marker   = new File(dir, "published.txt")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              sbt.Keys.publish / sbt.Keys.skip          := false,
              sbt.Keys.publishTo                        := Some(Resolver.file("local-test", dir)),
              ReleaseIO.releaseIOPublishArtifactsAction := {
                sbt.IO.write(marker, "published")
              }
            )
          )
        )
      }
      .use { fixture =>
        val ctx     = fixture.context(Seq("core"))
        val project = fixture.projectInfo("core")

        MonorepoPublishSteps.publishArtifacts.execute(ctx, project).map { _ =>
          assert(new File(fixture.dir, "published.txt").exists())
        }
      }
  }

  // ── publishArtifacts.validate success path ───────────────────────────

  test(
    "publishArtifacts.validate - pass when publishTo is set and publish not skipped"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-validate-pass") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core"),
            settings = Seq(
              MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks := true
            )
          ),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              sbt.Keys.publish / sbt.Keys.skip := false,
              sbt.Keys.publishTo               := Some(Resolver.file("local-test", dir))
            )
          )
        )
      }
      .use { fixture =>
        val ctx     = fixture.context(Seq("core"))
        val project = fixture.projectInfo("core")

        MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
      }
  }

  // ── checkSnapshotDependencies gaps ──────────────────────────────────

  test("checkSnapshotDependencies.validate - pass when no snapshot dependencies") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-no-snapshots") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              ReleaseIO.releaseIOSnapshotDependencies := Seq.empty[ModuleID]
            )
          )
        )
      }
      .use { fixture =>
        val ctx     = fixture.context(Seq("core"))
        val project = fixture.projectInfo("core")

        MonorepoPublishSteps.checkSnapshotDependencies.validate(ctx, project)
      }
  }

  test("checkSnapshotDependencies.execute - return context unchanged") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-snap-exec") { dir =>
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

        MonorepoPublishSteps.checkSnapshotDependencies.execute(ctx, project).map { result =>
          assert(result eq ctx)
        }
      }
  }

  // ── runClean success path ───────────────────────────────────────────

  test("runClean.execute - succeed when clean reports no failure") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-clean-ok") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject("core", coreBase)
        )
      }
      .use { fixture =>
        val ctx = fixture.context(Seq("core"))

        MonorepoStepIO.compose(Seq(MonorepoPublishSteps.runClean))(ctx).map { result =>
          assert(!result.failed)
        }
      }
  }

  // ── evaluateProjectTask error recovery ──────────────────────────────

  test(
    "publishArtifacts.execute - wrap NonFatal evaluation error in IllegalStateException"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-eval-error") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core"),
            settings = Seq(
              MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks := false
            )
          ),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(MonorepoStepTestCompat.throwingPublishSkipSetting)
          )
        )
      }
      .use { fixture =>
        val ctx = fixture.context(Seq("core"))

        MonorepoStepIO.compose(Seq(MonorepoPublishSteps.publishArtifacts))(ctx).map { result =>
          assert(result.failed)
          val aggregate = requireProjectFailures(result.failureCause)
          assertEquals(aggregate.failures.map(_.projectName), Seq("core"))
          assert(
            aggregate.failures.head.cause.exists(
              _.isInstanceOf[IllegalStateException]
            )
          )
          assert(
            aggregate.failures.head.cause.exists(
              _.getMessage.contains("Failed to evaluate publish / skip for core")
            )
          )
        }
      }
  }

  // ── runPerProjectInternal exception isolation ───────────────────────

  test(
    "per-project execution - isolate NonFatal exception and mark project failed"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-exception-isolation") { dir =>
        val coreBase = new File(dir, "core")
        val apiBase  = new File(dir, "api")
        coreBase.mkdirs()
        apiBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core", "api")),
          MonorepoSpecSupport.versionedProject("core", coreBase),
          MonorepoSpecSupport.versionedProject("api", apiBase)
        )
      }
      .use { fixture =>
        val ctx = fixture.context(Seq("core", "api"))

        val throwingStep = MonorepoStepIO.PerProject(
          name = "throwing-step",
          execute =
            (_, project) => IO.raiseError(new RuntimeException(s"${project.name} action blew up"))
        )

        MonorepoStepIO.compose(Seq(throwingStep))(ctx).map { result =>
          assert(result.failed)
          val aggregate = requireProjectFailures(result.failureCause)
          // Both projects fail — handleErrorWith isolates each error individually,
          // the fold continues because global ctx.failed is not set until propagateFailures
          assertEquals(aggregate.failures.map(_.projectName), Seq("core", "api"))
          assert(
            aggregate.failures.head.cause.exists(
              _.getMessage.contains("core action blew up")
            )
          )
          assert(
            aggregate.failures.last.cause.exists(
              _.getMessage.contains("api action blew up")
            )
          )
        }
      }
  }

  private def requireProjectFailures(
      cause: Option[Throwable]
  ): MonorepoProjectFailures =
    MonorepoSpecSupport.requireProjectFailures(cause)

}
