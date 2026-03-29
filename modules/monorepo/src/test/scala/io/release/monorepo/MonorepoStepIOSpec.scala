package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import io.release.internal.SbtCompat
import io.release.internal.SbtRuntime
import io.release.monorepo.steps.MonorepoPublishSteps
import io.release.monorepo.steps.MonorepoReleaseSteps
import io.release.monorepo.steps.MonorepoStepTestCompat
import munit.CatsEffectSuite
import sbt.AttributeKey
import sbt.Keys.*
import sbt.LocalProject
import sbt.Project
import sbt.ProjectRef
import sbt.State

import java.io.File
import java.nio.file.Files

class MonorepoStepIOSpec extends CatsEffectSuite {

  import MonorepoStepIOSpec.LoadedMonorepoFixture

  test("compose - run global validation before execute when no selection boundary exists") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val step = MonorepoStepIO.Global(
          name = "test-step",
          validate = _ => log.update(_ :+ "validate"),
          execute = c => log.update(_ :+ "execute").as(c)
        )

        MonorepoStepIO.compose(Seq(step))(ctx) *>
          log.get.map(obs => assertEquals(obs, List("validate", "execute")))
      }
    }
  }

  test("compose - abort on validation failure without running execute") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val step = MonorepoStepIO.Global(
          name = "failing-validate",
          validate = _ => IO.raiseError(new RuntimeException("validate failed")),
          execute = c => log.update(_ :+ "execute").as(c)
        )

        MonorepoStepIO.compose(Seq(step))(ctx).attempt.flatMap { result =>
          log.get.map { obs =>
            assert(result.isLeft)
            result.left.foreach {
              case e: RuntimeException =>
                assert(e.getMessage.contains("validate failed"))
              case other               => fail(s"Expected RuntimeException but got $other")
            }
            assertEquals(obs, List())
          }
        }
      }
    }
  }

  test("compose - iterate PerProject executes over all projects") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val projects = Seq(dummyProject("core"), dummyProject("api"))
        val pCtx     = ctx.withProjects(projects)

        val step = MonorepoStepIO.PerProject(
          name = "per-project-step",
          execute = (c, proj) => log.update(_ :+ proj.name).as(c)
        )

        MonorepoStepIO.compose(Seq(step))(pCtx) *>
          log.get.map(obs => assertEquals(obs, List("core", "api")))
      }
    }
  }

  test("compose - batch-validate then execute selected projects after the selection boundary") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val core     = dummyProject("core")
        val api      = dummyProject("api")
        val selected = api
        val pCtx     = ctx.withProjects(Seq(core, api))

        val setupStep = MonorepoStepIO
          .global("detect-or-select-projects")
          .withSelectionBoundary
          .execute(c => log.update(_ :+ "setup").as(c.withProjects(Seq(selected))))

        val stepA = MonorepoStepIO.PerProject(
          name = "step-a",
          validate = (_, project) => log.update(_ :+ s"validate-a:${project.name}"),
          execute = (c, project) => log.update(_ :+ s"execute-a:${project.name}").as(c)
        )
        val stepB = MonorepoStepIO.PerProject(
          name = "step-b",
          validate = (_, project) => log.update(_ :+ s"validate-b:${project.name}"),
          execute = (c, project) => log.update(_ :+ s"execute-b:${project.name}").as(c)
        )

        MonorepoStepIO.compose(Seq(setupStep, stepA, stepB))(pCtx) *>
          log.get.map { obs =>
            assertEquals(
              obs,
              List("setup", "validate-a:api", "validate-b:api", "execute-a:api", "execute-b:api")
            )
          }
      }
    }
  }

  test("compose - preserve custom process order around the selection boundary") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val core = dummyProject("core")
        val api  = dummyProject("api")
        val pCtx = ctx.withProjects(Seq(core, api))

        val setup       = MonorepoStepIO.Global(
          name = "custom-setup",
          validate = _ => log.update(_ :+ "validate-setup"),
          execute = c => log.update(_ :+ "execute-setup").as(c)
        )
        val boundary    = MonorepoStepIO
          .global("detect-or-select-projects")
          .withSelectionBoundary
          .execute(c => log.update(_ :+ "select").as(c.withProjects(Seq(api))))
        val afterPer    = MonorepoStepIO.PerProject(
          name = "custom-project",
          validate = (_, project) => log.update(_ :+ s"validate-project:${project.name}"),
          execute = (c, project) => log.update(_ :+ s"execute-project:${project.name}").as(c)
        )
        val afterGlobal = MonorepoStepIO.Global(
          name = "custom-global",
          validate = _ => log.update(_ :+ "validate-global"),
          execute = c => log.update(_ :+ "execute-global").as(c)
        )

        MonorepoStepIO.compose(Seq(setup, boundary, afterPer, afterGlobal))(pCtx) *>
          log.get.map { obs =>
            assertEquals(
              obs,
              List(
                "validate-setup",
                "execute-setup",
                "select",
                "validate-project:api",
                "validate-global",
                "execute-project:api",
                "execute-global"
              )
            )
          }
      }
    }
  }

  test("compose - thread MonorepoContext metadata through sequential execute steps") {
    contextResource.use { ctx =>
      val metadataKey = AttributeKey[String]("verified")
      val step1       = MonorepoStepIO.Global(
        name = "set-metadata",
        execute = c => IO.pure(c.withMetadata(metadataKey, "true"))
      )
      val step2       = MonorepoStepIO.Global(
        name = "read-metadata",
        execute = c =>
          if (c.metadata(metadataKey).contains("true")) IO.pure(c)
          else IO.raiseError(new RuntimeException("metadata not threaded"))
      )

      MonorepoStepIO.compose(Seq(step1, step2))(ctx).map { result =>
        assertEquals(result.metadata(metadataKey), Some("true"))
      }
    }
  }

  test("compose - mark entire release as failed when a global execute fails") {
    contextResource.use { ctx =>
      val step = MonorepoStepIO.Global(
        name = "global-fail",
        execute = _ => IO.raiseError(new RuntimeException("global failure"))
      )

      MonorepoStepIO.compose(Seq(step))(ctx).map { result =>
        assert(result.failed)
        result.failureCause match {
          case Some(err: RuntimeException) =>
            assertEquals(err.getMessage, "global failure")
          case other                       =>
            fail(s"Expected RuntimeException failure cause but got $other")
        }
      }
    }
  }

  test("compose - preserve per-project failure causes in the final context") {
    contextResource.use { ctx =>
      val projects = Seq(dummyProject("core"), dummyProject("api"))
      val pCtx     = ctx.withProjects(projects)

      val failingStep = MonorepoStepIO.PerProject(
        name = "failing-step",
        execute = (c, project) =>
          if (project.name == "core")
            IO.raiseError(new RuntimeException("core failed"))
          else IO.pure(c)
      )

      MonorepoStepIO.compose(Seq(failingStep))(pCtx).map { result =>
        val aggregate = requireProjectFailures(result.failureCause)
        assert(result.failed)
        assert(aggregate.failures.map(_.projectName).contains("core"))
        assertEquals(
          aggregate.failures
            .find(_.projectName == "core")
            .flatMap(_.cause)
            .map(_.getMessage),
          Some("core failed")
        )
      }
    }
  }

  test("compose - per-project step returning ctx.failWith stops later projects") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val projects = Seq(dummyProject("core"), dummyProject("api"))
        val pCtx     = ctx.withProjects(projects)

        val failStep = MonorepoStepIO.PerProject(
          name = "fail-with-step",
          execute = (c, project) =>
            observed.update(_ :+ project.name).as {
              if (project.name == "core")
                c.failWith(new RuntimeException("fatal stop"))
              else c
            }
        )

        MonorepoStepIO.compose(Seq(failStep))(pCtx).flatMap { result =>
          observed.get.map { obs =>
            assert(result.failed)
            assertEquals(obs, List("core"))
          }
        }
      }
    }
  }

  test(
    "compose - cross-build single-version per-project step validates and executes once and restores the entry scalaVersion"
  ) {
    loadedContextWithProjectsResource("monorepo-step-single-cross") { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      LoadedMonorepoFixture(
        projects = Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"))
            .settings(
              scalaVersion     := TestSupport.CurrentScalaVersion
            ),
          Project("core", coreBase).settings(
            scalaVersion       := TestSupport.CurrentScalaVersion,
            crossScalaVersions := Seq(TestSupport.alternateScalaVersion)
          )
        ),
        selectedProjectIds = Seq("core")
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
    loadedContextWithProjectsResource("monorepo-step-multi-cross") { dir =>
      val coreBase = new File(dir, "core")
      val apiBase  = new File(dir, "api")
      coreBase.mkdirs()
      apiBase.mkdirs()

      LoadedMonorepoFixture(
        projects = Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"), LocalProject("api"))
            .settings(
              scalaVersion     := TestSupport.CurrentScalaVersion
            ),
          Project("core", coreBase).settings(
            scalaVersion       := TestSupport.CurrentScalaVersion,
            crossScalaVersions := Seq(
              TestSupport.CurrentScalaVersion,
              TestSupport.alternateScalaVersion
            )
          ),
          Project("api", apiBase).settings(
            scalaVersion       := TestSupport.CurrentScalaVersion,
            crossScalaVersions := Seq(TestSupport.CurrentScalaVersion)
          )
        ),
        selectedProjectIds = Seq("core", "api")
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

  test(
    "compose - compatibility global wrapper delegates wrapped per-project validation and cross-build execution"
  ) {
    loadedContextWithProjectsResource("monorepo-step-compatibility-wrapper") { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      LoadedMonorepoFixture(
        projects = Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"))
            .settings(
              scalaVersion     := TestSupport.CurrentScalaVersion
            ),
          Project("core", coreBase).settings(
            scalaVersion       := TestSupport.CurrentScalaVersion,
            crossScalaVersions := Seq(
              TestSupport.CurrentScalaVersion,
              TestSupport.alternateScalaVersion
            )
          )
        ),
        selectedProjectIds = Seq("core")
      )
    }.use { baseCtx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val wrappedStep = MonorepoStepIO.PerProject(
          name = "wrapped-tag-release",
          validate = (ctx, project) =>
            scalaVersionOf(ctx.state)
              .flatMap(version => observed.update(_ :+ s"validate:${project.name}:$version")),
          execute = (ctx, project) =>
            scalaVersionOf(ctx.state)
              .flatMap(version => observed.update(_ :+ s"execute:${project.name}:$version"))
              .as(ctx),
          enableCrossBuild = true
        )
        val wrapper     = MonorepoReleaseSteps.compatibilityGlobalStep("tag-releases", wrappedStep)
        val ctx         = MonorepoSpecSupport.withPlan(
          baseCtx,
          MonorepoSpecSupport.releasePlan(
            selectionMode = SelectionMode.AllChanged,
            flags = MonorepoSpecSupport.defaultFlags.copy(crossBuild = true)
          )
        )

        MonorepoStepIO.compose(Seq(wrapper))(ctx).flatMap { result =>
          for {
            events          <- observed.get
            restoredVersion <- scalaVersionOf(result.state)
          } yield {
            assertEquals(
              events,
              List(
                s"validate:core:${TestSupport.CurrentScalaVersion}",
                s"validate:core:${TestSupport.alternateScalaVersion}",
                s"execute:core:${TestSupport.CurrentScalaVersion}",
                s"execute:core:${TestSupport.alternateScalaVersion}"
              )
            )
            assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
          }
        }
      }
    }
  }

  test("compose - fail validation when cross-build is enabled with empty crossScalaVersions") {
    loadedContextWithProjectsResource("monorepo-step-empty-cross") { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      LoadedMonorepoFixture(
        projects = Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"))
            .settings(
              scalaVersion     := TestSupport.CurrentScalaVersion
            ),
          Project("core", coreBase).settings(
            scalaVersion       := TestSupport.CurrentScalaVersion,
            crossScalaVersions := Seq.empty
          )
        ),
        selectedProjectIds = Seq("core")
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

    loadedContextWithProjectsResource("monorepo-step-cross-failure") { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      LoadedMonorepoFixture(
        projects = Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"))
            .settings(
              scalaVersion     := TestSupport.CurrentScalaVersion
            ),
          Project("core", coreBase).settings(
            scalaVersion       := TestSupport.CurrentScalaVersion,
            crossScalaVersions := Seq(
              TestSupport.CurrentScalaVersion,
              TestSupport.alternateScalaVersion
            )
          )
        ),
        selectedProjectIds = Seq("core")
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
    "compose - detect FailureCommand sentinel after per-project execution and skip later steps"
  ) {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val projects = Seq(dummyProject("core"), dummyProject("api"))
        val pCtx     = ctx.withProjects(projects)

        val injectFailure = MonorepoStepIO.PerProject(
          name = "inject-failure-command",
          execute = (c, project) =>
            observed.update(_ :+ project.name).as {
              if (project.name == "core")
                c.withState(
                  c.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil)
                )
              else c
            }
        )

        val skipped = MonorepoStepIO.Global(
          name = "skipped-after-failure",
          execute = c => observed.update(_ :+ "after").as(c)
        )

        MonorepoStepIO
          .compose(Seq(injectFailure, skipped))(pCtx)
          .flatMap { result =>
            observed.get.map { obs =>
              assert(result.failed)
              val aggregate = requireProjectFailures(result.failureCause)
              assertEquals(aggregate.failures.map(_.projectName), Seq("core"))
              assert(
                aggregate.failures.head.cause.exists(
                  _.getMessage.contains("sbt task reported failure via FailureCommand")
                )
              )
              assertEquals(obs, List("core", "api"))
              assertEquals(result.state.remainingCommands, Nil)
              assertEquals(result.state.onFailure, None)
            }
          }
      }
    }
  }

  test(
    "compose - cross-build short-circuits remaining versions when a version fails via context"
  ) {
    loadedContextWithProjectsResource("monorepo-step-cross-short-circuit") { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      LoadedMonorepoFixture(
        projects = Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"))
            .settings(
              scalaVersion     := TestSupport.CurrentScalaVersion
            ),
          Project("core", coreBase).settings(
            scalaVersion       := TestSupport.CurrentScalaVersion,
            crossScalaVersions := Seq(
              TestSupport.CurrentScalaVersion,
              TestSupport.alternateScalaVersion
            )
          )
        ),
        selectedProjectIds = Seq("core")
      )
    }.use { ctx =>
      val step = MonorepoStepIO.PerProject(
        name = "cross-step",
        execute = (c, project) =>
          scalaVersionOf(c.state).flatMap { version =>
            appendCurrentScalaVersion(
              new File(project.baseDir, "short-circuit.txt"),
              c.state
            ).as(c.failWith(new IllegalStateException("simulated task failure")))
          },
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

  test(
    "compose - cross-build short-circuit restores entry Scala version"
  ) {
    loadedContextWithProjectsResource("monorepo-step-cross-short-circuit-restore") { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      LoadedMonorepoFixture(
        projects = Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"))
            .settings(
              scalaVersion     := TestSupport.CurrentScalaVersion
            ),
          Project("core", coreBase).settings(
            scalaVersion       := TestSupport.CurrentScalaVersion,
            crossScalaVersions := Seq(
              TestSupport.alternateScalaVersion,
              TestSupport.CurrentScalaVersion
            )
          )
        ),
        selectedProjectIds = Seq("core")
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
    loadedContextWithProjectsResource("monorepo-step-cross-failure-command") { dir =>
      val coreBase = new File(dir, "core")
      val marker   = new File(coreBase, "test-ran.txt")
      coreBase.mkdirs()

      LoadedMonorepoFixture(
        projects = Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"))
            .settings(
              scalaVersion     := TestSupport.CurrentScalaVersion
            ),
          Project("core", coreBase).settings(
            scalaVersion       := TestSupport.CurrentScalaVersion,
            crossScalaVersions := Seq(
              TestSupport.CurrentScalaVersion,
              TestSupport.alternateScalaVersion
            ),
            MonorepoStepTestCompat.failureCommandTestTaskSetting(marker)
          )
        ),
        selectedProjectIds = Seq("core")
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

  test(
    "compose - cross validation runs per-version while non-cross validation runs once"
  ) {
    loadedContextWithProjectsResource("monorepo-step-cross-validation-mix") { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      LoadedMonorepoFixture(
        projects = Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"))
            .settings(
              scalaVersion     := TestSupport.CurrentScalaVersion
            ),
          Project("core", coreBase).settings(
            scalaVersion       := TestSupport.CurrentScalaVersion,
            crossScalaVersions := Seq(
              TestSupport.CurrentScalaVersion,
              TestSupport.alternateScalaVersion
            )
          )
        ),
        selectedProjectIds = Seq("core")
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

  test(
    "compose - two sequential cross-build steps each iterate all versions independently"
  ) {
    loadedContextWithProjectsResource("monorepo-step-sequential-cross") { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      LoadedMonorepoFixture(
        projects = Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"))
            .settings(
              scalaVersion     := TestSupport.CurrentScalaVersion
            ),
          Project("core", coreBase).settings(
            scalaVersion       := TestSupport.CurrentScalaVersion,
            crossScalaVersions := Seq(
              TestSupport.CurrentScalaVersion,
              TestSupport.alternateScalaVersion
            )
          )
        ),
        selectedProjectIds = Seq("core")
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

  private def dummyProject(name: String): ProjectReleaseInfo =
    MonorepoTestSupport.dummyProject(name)

  private def scalaVersionOf(state: State): IO[String] =
    IO.blocking(SbtRuntime.extracted(state).get(scalaVersion))

  private def appendCurrentScalaVersion(file: File, state: State): IO[Unit] =
    scalaVersionOf(state).flatMap(version => IO.blocking(sbt.IO.append(file, s"$version\n")))

  private def requireProjectFailures(
      cause: Option[Throwable]
  ): MonorepoProjectFailures =
    MonorepoSpecSupport.requireProjectFailures(cause)

  private def loadedContextWithProjectsResource(
      prefix: String
  )(fixtureFor: File => LoadedMonorepoFixture): Resource[IO, MonorepoContext] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val fixture  = fixtureFor(dir)
        val state    = TestSupport.loadedState(
          dir,
          fixture.projects,
          currentProjectId = Some("root")
        )
        val refsById = {
          val extracted = SbtRuntime.extracted(state)
          (extracted.currentRef +: extracted.currentProject.aggregate)
            .map(ref => ref.project -> ref)
            .toMap
        }

        MonorepoContext(
          state = state,
          projects =
            fixture.selectedProjectIds.map(id => projectInfo(refsById, fixture.projects, id))
        )
      }
    }

  private def projectInfo(
      refsById: Map[String, ProjectRef],
      projects: Seq[Project],
      id: String
  ): ProjectReleaseInfo =
    projects.find(_.id == id) match {
      case Some(project) =>
        ProjectReleaseInfo(
          ref = refsById.getOrElse(id, fail(s"Expected loaded ProjectRef for '$id'")),
          name = id,
          baseDir = project.base,
          versionFile = new File(project.base, "version.sbt")
        )
      case None          =>
        fail(s"Expected loaded project '$id'")
    }

  private val contextResource: Resource[IO, MonorepoContext] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("monorepo-step-spec").toFile))(dir =>
        IO.blocking(TestSupport.deleteRecursively(dir))
      )
      .map(dir => MonorepoContext(state = TestSupport.dummyState(dir)))
}

private object MonorepoStepIOSpec {
  final case class LoadedMonorepoFixture(
      projects: Seq[Project],
      selectedProjectIds: Seq[String]
  )
}
