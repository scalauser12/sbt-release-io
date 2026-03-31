package io.release.monorepo

import cats.effect.IO
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import io.release.monorepo.steps.MonorepoPublishSteps
import io.release.monorepo.steps.MonorepoStepTestCompat
import munit.CatsEffectSuite
import sbt.AttributeKey
import sbt.LocalProject
import sbt.Project
import sbt.Keys.*

import java.io.File

class MonorepoStepIOCrossBuildSpec extends CatsEffectSuite with MonorepoStepIOSpecSupport {

  test(
    "compose - cross-build single-version per-project step validates and executes once and restores the entry scalaVersion"
  ) {
    loadedContextResource("monorepo-step-single-cross", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(TestSupport.alternateScalaVersion)
        )
      )
    }.use { ctx =>
      val step = MonorepoStepIO.PerProject(
        name = "cross-step",
        validate = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "validate.txt"), c.state),
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "execute.txt"), c.state).as(c),
        enableCrossBuild = true
      )

      MonorepoStepIO.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
        val project = MonorepoSpecSupport.projectNamed(result.projects, "core")

        for {
          restoredVersion <- scalaVersionOf(result.state)
          validateLines   <-
            MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "validate.txt"))
          executeLines    <-
            MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "execute.txt"))
        } yield {
          assertEquals(validateLines, List(TestSupport.alternateScalaVersion))
          assertEquals(executeLines, List(TestSupport.alternateScalaVersion))
          assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
        }
      }
    }
  }

  test(
    "compose - cross-build multi-version per-project steps run once per configured version and later non-cross steps still run once"
  ) {
    loadedContextResource("monorepo-step-multi-cross", Seq("core", "api")) { dir =>
      val coreBase = new File(dir, "core")
      val apiBase  = new File(dir, "api")
      coreBase.mkdirs()
      apiBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"), LocalProject("api"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        ),
        Project("api", apiBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(TestSupport.CurrentScalaVersion)
        )
      )
    }.use { ctx =>
      val crossStep = MonorepoStepIO.PerProject(
        name = "cross-step",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "cross-invocations.txt"), c.state)
            .as(c),
        enableCrossBuild = true
      )
      val plainStep = MonorepoStepIO.PerProject(
        name = "plain-step",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "plain-invocations.txt"), c.state)
            .as(c)
      )

      MonorepoStepIO.compose(Seq(crossStep, plainStep), crossBuild = true)(ctx).flatMap { result =>
        val core = MonorepoSpecSupport.projectNamed(result.projects, "core")
        val api  = MonorepoSpecSupport.projectNamed(result.projects, "api")

        for {
          restoredVersion <- scalaVersionOf(result.state)
          coreCross       <-
            MonorepoSpecSupport.readNonEmptyLines(new File(core.baseDir, "cross-invocations.txt"))
          apiCross        <-
            MonorepoSpecSupport.readNonEmptyLines(new File(api.baseDir, "cross-invocations.txt"))
          corePlain       <-
            MonorepoSpecSupport.readNonEmptyLines(new File(core.baseDir, "plain-invocations.txt"))
          apiPlain        <-
            MonorepoSpecSupport.readNonEmptyLines(new File(api.baseDir, "plain-invocations.txt"))
        } yield {
          assertEquals(
            coreCross,
            List(TestSupport.CurrentScalaVersion, TestSupport.alternateScalaVersion)
          )
          assertEquals(apiCross, List(TestSupport.CurrentScalaVersion))
          assertEquals(corePlain, List(TestSupport.CurrentScalaVersion))
          assertEquals(apiPlain, List(TestSupport.CurrentScalaVersion))
          assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
        }
      }
    }
  }

  test("compose - fail validation when cross-build is enabled with empty crossScalaVersions") {
    loadedContextResource("monorepo-step-empty-cross", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq.empty
        )
      )
    }.use { ctx =>
      val marker = new File(
        MonorepoSpecSupport.projectNamed(ctx.projects, "core").baseDir,
        "should-not-run.txt"
      )
      val step   = MonorepoStepIO.PerProject(
        name = "cross-step",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "should-not-run.txt"), c.state).as(c),
        enableCrossBuild = true
      )

      assertFailure[IllegalStateException, MonorepoContext](
        MonorepoStepIO.compose(Seq(step), crossBuild = true)(ctx)
      ) { err =>
        assert(err.getMessage.contains("Cross-build enabled but core has empty crossScalaVersions"))
        assert(!marker.exists())
      }
    }
  }

  test("compose - restore the entry scalaVersion after a cross-build iteration fails") {
    val metadataKey = AttributeKey[String]("cross-build-metadata-marker")

    loadedContextResource("monorepo-step-cross-failure", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        )
      )
    }.use { ctx =>
      val step = MonorepoStepIO.PerProject(
        name = "cross-step",
        execute = (c, project) =>
          scalaVersionOf(c.state).flatMap { version =>
            appendCurrentScalaVersion(new File(project.baseDir, "cross-failure.txt"), c.state) *>
              (if (version == TestSupport.alternateScalaVersion)
                 if (c.metadata(metadataKey).contains("updated"))
                   IO.raiseError(new RuntimeException("boom"))
                 else
                   IO.raiseError(new RuntimeException("missing metadata before failure"))
               else
                 IO.pure(c.withMetadata(metadataKey, "updated")))
          },
        enableCrossBuild = true
      )

      MonorepoStepIO.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
        val project = MonorepoSpecSupport.projectNamed(result.projects, "core")

        for {
          restoredVersion <- scalaVersionOf(result.state)
          failureLines    <-
            MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "cross-failure.txt"))
        } yield {
          assert(result.failed)
          assert(project.failed)
          assertEquals(
            failureLines,
            List(TestSupport.CurrentScalaVersion, TestSupport.alternateScalaVersion)
          )
          assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
          val aggregate = requireProjectFailures(result.failureCause)
          assertEquals(
            aggregate.failures.find(_.projectName == "core").flatMap(_.cause).map(_.getMessage),
            Some("boom")
          )
        }
      }
    }
  }

  test(
    "compose - cross-build short-circuits remaining versions when a version fails via context"
  ) {
    loadedContextResource("monorepo-step-cross-short-circuit", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        )
      )
    }.use { ctx =>
      val step = MonorepoStepIO.PerProject(
        name = "cross-step",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "short-circuit.txt"), c.state)
            .as(c.failWith(new IllegalStateException("simulated task failure"))),
        enableCrossBuild = true
      )

      MonorepoStepIO.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
        val project = MonorepoSpecSupport.projectNamed(result.projects, "core")

        MonorepoSpecSupport
          .readNonEmptyLines(new File(project.baseDir, "short-circuit.txt"))
          .map { lines =>
            assert(result.failed)
            assertEquals(lines, List(TestSupport.CurrentScalaVersion))
          }
      }
    }
  }

  test("compose - cross-build short-circuit restores entry Scala version") {
    loadedContextResource("monorepo-step-cross-short-circuit-restore", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.alternateScalaVersion,
            TestSupport.CurrentScalaVersion
          )
        )
      )
    }.use { ctx =>
      val step = MonorepoStepIO.PerProject(
        name = "cross-step",
        execute = (c, _) => IO.pure(c.failWith(new IllegalStateException("fail on first version"))),
        enableCrossBuild = true
      )

      MonorepoStepIO.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
        scalaVersionOf(result.state).map { finalVersion =>
          assert(result.failed)
          assertEquals(finalVersion, TestSupport.CurrentScalaVersion)
        }
      }
    }
  }

  test(
    "compose - cross-build short-circuits remaining versions when a project is marked failed via FailureCommand"
  ) {
    loadedContextResource("monorepo-step-cross-failure-command", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      val marker   = new File(coreBase, "test-ran.txt")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          ),
          MonorepoStepTestCompat.failureCommandTestTaskSetting(marker)
        )
      )
    }.use { ctx =>
      MonorepoStepIO
        .compose(Seq(MonorepoPublishSteps.runTests), crossBuild = true)(ctx)
        .flatMap { result =>
          scalaVersionOf(result.state).map { finalVersion =>
            val core      = MonorepoSpecSupport.projectNamed(result.projects, "core")
            assert(result.failed, "expected global failure after propagation")
            assert(core.failed, "expected project-level failure")
            assertEquals(finalVersion, TestSupport.CurrentScalaVersion)
            val aggregate = requireProjectFailures(result.failureCause)
            assertEquals(aggregate.failures.map(_.projectName), Seq("core"))
          }
        }
    }
  }

  test("compose - cross validation runs per-version while non-cross validation runs once") {
    loadedContextResource("monorepo-step-cross-validation-mix", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        )
      )
    }.use { ctx =>
      val crossStep = MonorepoStepIO.PerProject(
        name = "cross-step",
        validate = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "cross-validate.txt"), c.state),
        execute = (c, _) => IO.pure(c),
        enableCrossBuild = true
      )
      val plainStep = MonorepoStepIO.PerProject(
        name = "plain-step",
        validate = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "plain-validate.txt"), c.state),
        execute = (c, _) => IO.pure(c)
      )

      MonorepoStepIO.compose(Seq(crossStep, plainStep), crossBuild = true)(ctx).flatMap { result =>
        val project = MonorepoSpecSupport.projectNamed(result.projects, "core")

        for {
          crossLines <-
            MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "cross-validate.txt"))
          plainLines <-
            MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "plain-validate.txt"))
        } yield {
          assertEquals(
            crossLines,
            List(TestSupport.CurrentScalaVersion, TestSupport.alternateScalaVersion)
          )
          assertEquals(plainLines, List(TestSupport.CurrentScalaVersion))
        }
      }
    }
  }

  test("compose - two sequential cross-build steps each iterate all versions independently") {
    loadedContextResource("monorepo-step-sequential-cross", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        )
      )
    }.use { ctx =>
      val step1 = MonorepoStepIO.PerProject(
        name = "cross-step-1",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "step1.txt"), c.state).as(c),
        enableCrossBuild = true
      )
      val step2 = MonorepoStepIO.PerProject(
        name = "cross-step-2",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "step2.txt"), c.state).as(c),
        enableCrossBuild = true
      )

      MonorepoStepIO.compose(Seq(step1, step2), crossBuild = true)(ctx).flatMap { result =>
        val project = MonorepoSpecSupport.projectNamed(result.projects, "core")

        for {
          restoredVersion <- scalaVersionOf(result.state)
          step1Lines      <-
            MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "step1.txt"))
          step2Lines      <-
            MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "step2.txt"))
        } yield {
          assertEquals(
            step1Lines,
            List(TestSupport.CurrentScalaVersion, TestSupport.alternateScalaVersion)
          )
          assertEquals(
            step2Lines,
            List(TestSupport.CurrentScalaVersion, TestSupport.alternateScalaVersion)
          )
          assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
        }
      }
    }
  }
}
