package io.release.monorepo.steps

import cats.effect.{IO, Resource}
import io.release.ReleaseIO
import io.release.internal.PublishValidation
import io.release.monorepo.{
  MonorepoProjectFailures,
  MonorepoReleaseIO,
  MonorepoSpecSupport,
  MonorepoStepIO
}
import io.release.TestAssertions.assertFailure
import munit.CatsEffectSuite
import sbt.{Project, Resolver, *}

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
          val coreRun = new File(
            MonorepoSpecSupport.projectNamed(result.projects, "core").baseDir,
            "test-ran.txt"
          )
          val apiRun  = new File(
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
              _.getMessage.contains("core / Test /")
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

  private def requireProjectFailures(
      cause: Option[Throwable]
  ): MonorepoProjectFailures =
    cause match {
      case Some(aggregate)
          if classOf[MonorepoProjectFailures].isInstance(aggregate) =>
        classOf[MonorepoProjectFailures].cast(aggregate)
      case other =>
        fail(s"Expected MonorepoProjectFailures but got $other")
    }

}
