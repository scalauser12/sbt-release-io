package io.release

import cats.effect.{IO, Ref, Resource}
import io.release.internal.{CoreExecutionState, CoreReleasePlan, ExecutionFlags, SbtCompat, SbtRuntime}
import munit.CatsEffectSuite
import sbt.Def.*
import sbt.{AttributeKey, Def, InputKey, Keys, LocalProject, Project, State, TaskKey, inputKey, taskKey}

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ReleaseStepIOSpec extends CatsEffectSuite {

  test("compose - run validations before executes and fail fast on validation error") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val step1 = ReleaseStepIO(
          name = "step1",
          execute = c => observed.update(_ :+ "execute1").as(c),
          validate = _ => observed.update(_ :+ "validate1")
        )

        val step2 = ReleaseStepIO(
          name = "step2",
          execute = c => observed.update(_ :+ "execute2").as(c),
          validate = _ =>
            observed.update(_ :+ "validate2") *>
              IO.raiseError(new RuntimeException("validation failed"))
        )

        ReleaseStepIO
          .compose(Seq(step1, step2), crossBuild = false)(ctx)
          .attempt
          .flatMap { result =>
            observed.get.map { obs =>
              assert(result.isLeft)
              result.left.foreach {
                case e: RuntimeException =>
                  assert(e.getMessage.contains("validation failed"))
                case other               => fail(s"Expected RuntimeException but got $other")
              }
              assertEquals(obs, List("validate1", "validate2"))
            }
          }
      }
    }
  }

  test("compose - mark the release as failed when an execute throws") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val failing = ReleaseStepIO.io("failing") { _ =>
          observed.update(_ :+ "execute1") *> IO.raiseError(new RuntimeException("boom"))
        }

        val skipped = ReleaseStepIO.io("skipped") { c =>
          observed.update(_ :+ "execute2").as(c)
        }

        ReleaseStepIO
          .compose(Seq(failing, skipped), crossBuild = false)(ctx)
          .flatMap { result =>
            observed.get.map { obs =>
              assert(result.failed)
              assert(result.failureCause.isDefined)
              assertEquals(obs, List("execute1"))
            }
          }
      }
    }
  }

  test("compose - clear onFailure after successful compose") {
    contextResource.use { ctx =>
      val step = ReleaseStepIO.io("noop") { c => IO.pure(c) }

      ReleaseStepIO
        .compose(Seq(step), crossBuild = false)(ctx)
        .map { result =>
          assertEquals(result.state.onFailure, None)
        }
    }
  }

  test("compose - detect FailureCommand sentinel and skip subsequent executes") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val injectFailure = ReleaseStepIO.io("inject-failure-command") { c =>
          observed
            .update(_ :+ "execute1")
            .as(
              c.copy(state = c.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil))
            )
        }

        val skipped = ReleaseStepIO.io("skipped") { c =>
          observed.update(_ :+ "execute2").as(c)
        }

        ReleaseStepIO
          .compose(Seq(injectFailure, skipped), crossBuild = false)(ctx)
          .flatMap { result =>
            observed.get.map { obs =>
              assert(result.failed)
              assertEquals(result.failureCause, None)
              assertEquals(obs, List("execute1"))
              assertEquals(result.state.remainingCommands, Nil)
              assertEquals(result.state.onFailure, None)
            }
          }
      }
    }
  }

  test("compose - cross-build step runs once for a single configured Scala version and restores the entry version") {
    loadedContextResource(
      "release-step-io-single-cross",
      _.settings(
        Keys.scalaVersion      := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Seq(TestSupport.alternateScalaVersion)
      )
    ).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val step = ReleaseStepIO(
          name = "cross-step",
          execute = c =>
            scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"execute:$v").as(c)),
          validate = c =>
            scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"validate:$v")),
          enableCrossBuild = true
        )

        ReleaseStepIO
          .compose(Seq(step), crossBuild = true)(ctx)
          .flatMap { result =>
            for {
              obs          <- observed.get
              finalVersion <- scalaVersionOf(result.state)
            } yield {
              assertEquals(
                obs,
                List(
                  s"validate:${TestSupport.alternateScalaVersion}",
                  s"execute:${TestSupport.alternateScalaVersion}"
                )
              )
              assertEquals(finalVersion, TestSupport.CurrentScalaVersion)
            }
          }
      }
    }
  }

  test("compose - cross-build step runs for each configured Scala version while non-cross steps still run once") {
    loadedContextResource(
      "release-step-io-multi-cross",
      _.settings(
        Keys.scalaVersion      := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Seq(
          TestSupport.CurrentScalaVersion,
          TestSupport.alternateScalaVersion
        )
      )
    ).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val crossStep = ReleaseStepIO(
          name = "cross-step",
          execute = c =>
            scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"execute:$v").as(c)),
          validate = c =>
            scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"validate:$v")),
          enableCrossBuild = true
        )
        val plainStep = ReleaseStepIO.io("plain-step") { c =>
          scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"plain:$v").as(c))
        }

        ReleaseStepIO
          .compose(Seq(crossStep, plainStep), crossBuild = true)(ctx)
          .flatMap { result =>
            for {
              obs          <- observed.get
              finalVersion <- scalaVersionOf(result.state)
            } yield {
              assertEquals(
                obs,
                List(
                  s"validate:${TestSupport.CurrentScalaVersion}",
                  s"validate:${TestSupport.alternateScalaVersion}",
                  s"execute:${TestSupport.CurrentScalaVersion}",
                  s"execute:${TestSupport.alternateScalaVersion}",
                  s"plain:${TestSupport.CurrentScalaVersion}"
                )
              )
              assertEquals(finalVersion, TestSupport.CurrentScalaVersion)
            }
          }
      }
    }
  }

  test("compose - fail validation when cross-build is enabled with no configured cross versions") {
    loadedContextResource(
      "release-step-io-empty-cross",
      _.settings(
        Keys.scalaVersion      := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Nil
      )
    ).use { ctx =>
      Ref.of[IO, Boolean](false).flatMap { executed =>
        val step = ReleaseStepIO(
          name = "cross-step",
          execute = c => executed.set(true).as(c),
          enableCrossBuild = true
        )

        ReleaseStepIO
          .compose(Seq(step), crossBuild = true)(ctx)
          .attempt
          .flatMap { result =>
            executed.get.map { didExecute =>
              result match {
                case Left(err: IllegalStateException) =>
                  assertEquals(
                    err.getMessage,
                    "[release-io] Cross-build enabled but crossScalaVersions is empty"
                  )
                case other                            =>
                  fail(s"Expected IllegalStateException but got $other")
              }
              assertEquals(didExecute, false)
            }
          }
      }
    }
  }

  test("compose - restore the entry Scala version after a cross-build execute fails") {
    loadedContextResource(
      "release-step-io-cross-failure",
      _.settings(
        Keys.scalaVersion      := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Seq(
          TestSupport.CurrentScalaVersion,
          TestSupport.alternateScalaVersion
        )
      )
    ).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val step = ReleaseStepIO.io("cross-step") { c =>
          scalaVersionOf(c.state).flatMap { version =>
            observed.update(_ :+ s"execute:$version") *>
              (if (version == TestSupport.alternateScalaVersion)
                 IO.raiseError(new RuntimeException("boom"))
               else IO.pure(c))
          }
        }.copy(enableCrossBuild = true)

        ReleaseStepIO
          .compose(Seq(step), crossBuild = true)(ctx)
          .flatMap { result =>
            for {
              obs          <- observed.get
              finalVersion <- scalaVersionOf(result.state)
            } yield {
              assert(result.failed)
              assertEquals(
                obs,
                List(
                  s"execute:${TestSupport.CurrentScalaVersion}",
                  s"execute:${TestSupport.alternateScalaVersion}"
                )
              )
              assert(result.failureCause.exists(_.getMessage.contains("boom")))
              assertEquals(finalVersion, TestSupport.CurrentScalaVersion)
            }
          }
      }
    }
  }

  test("ReleaseContext metadata - store typed values immutably") {
    contextResource.use { ctx =>
      IO {
        val releaseCompleted = AttributeKey[Boolean]("releaseCompleted")
        val attemptCount     = AttributeKey[Int]("attemptCount")
        val updated          = ctx
          .withMetadata(releaseCompleted, true)
          .withMetadata(attemptCount, 2)
        val removed          = updated.withoutMetadata(releaseCompleted)

        assertEquals(ctx.metadata(releaseCompleted), None)
        assertEquals(updated.metadata(releaseCompleted), Some(true))
        assertEquals(updated.metadata(attemptCount), Some(2))
        assertEquals(removed.metadata(releaseCompleted), None)
        assertEquals(removed.metadata(attemptCount), Some(2))
      }
    }
  }

  test("ReleaseContext internal execution state - survive state replacement") {
    contextResource.use { ctx =>
      IO {
        val plan    = CoreReleasePlan(
          flags = ExecutionFlags(
            useDefaults = true,
            skipTests = false,
            skipPublish = false,
            interactive = false,
            crossBuild = false
          ),
          releaseVersionOverride = Some("1.0.0"),
          nextVersionOverride = Some("1.0.1-SNAPSHOT"),
          tagDefault = Some("k")
        )
        val updated = ctx
          .withExecutionState(CoreExecutionState(plan))
          .withState(ctx.state.copy(onFailure = None))

        assertEquals(updated.executionState.map(_.plan.tagDefault), Some(Some("k")))
        assertEquals(updated.useDefaults, true)
      }
    }
  }

  test("command steps - surface command parse failures for fromCommand") {
    contextResource.use { ctx =>
      val step = ReleaseStepIO.fromCommand("this-command-does-not-exist")
      step.execute(ctx).attempt.map {
        case Left(e: RuntimeException) =>
          assert(e.getMessage.contains("Failed to parse command"))
        case other                     =>
          fail(s"Expected RuntimeException but got $other")
      }
    }
  }

  test("command steps - surface command parse failures for fromCommandAndRemaining") {
    contextResource.use { ctx =>
      val step = ReleaseStepIO.fromCommandAndRemaining("this-command-does-not-exist")
      step.execute(ctx).attempt.map {
        case Left(e: RuntimeException) =>
          assert(e.getMessage.contains("Failed to parse command"))
        case other                     =>
          fail(s"Expected RuntimeException but got $other")
      }
    }
  }

  test("task steps - run fromTask and thread back the updated state") {
    loadedContextResource(
      "release-step-io-from-task",
      _.settings(
        stateUpdateTask := {
          val marker = new File(Keys.baseDirectory.value, "from-task.txt")
          sbt.IO.write(marker, "task-ran")
          "task-ran"
        }
      )
    ).use { ctx =>
      ReleaseStepIO.fromTask(stateUpdateTask).execute(ctx).map { result =>
        val marker = new File(SbtRuntime.extracted(result.state).get(Keys.baseDirectory), "from-task.txt")
        assertEquals(readFile(marker), "task-ran")
      }
    }
  }

  test("task steps - run fromInputTask with explicit args and thread back the updated state") {
    loadedContextResource(
      "release-step-io-from-input-task",
      _.settings(
        stateUpdateInputTask := {
          val parsed = Def.spaceDelimited().parsed.mkString(":")
          val marker = new File(Keys.baseDirectory.value, "from-input-task.txt")
          sbt.IO.write(marker, parsed)
          parsed
        }
      )
    ).use { ctx =>
      ReleaseStepIO
        .fromInputTask(stateUpdateInputTask, args = " alpha beta")
        .execute(ctx)
        .map { result =>
          val marker =
            new File(SbtRuntime.extracted(result.state).get(Keys.baseDirectory), "from-input-task.txt")
          assertEquals(readFile(marker), "alpha:beta")
        }
    }
  }

  test("task steps - run fromTaskAggregated across aggregated projects") {
    loadedContextWithProjectsResource("release-step-io-from-task-aggregated") { dir =>
      val markerFor = (projectId: String) => new File(dir, s"aggregated-task-$projectId.txt")
      val sharedSettings = Seq(
        aggregatedStateTask := {
          val value  = Keys.thisProjectRef.value.project
          val marker = markerFor(value)
          sbt.IO.write(marker, value)
          value
        }
      )
      val api            = Project("api", new File(dir, "api")).settings(sharedSettings*)
      val core           = Project("core", new File(dir, "core")).settings(sharedSettings*)
      val root           = Project("root", dir)
        .aggregate(LocalProject("api"), LocalProject("core"))
        .settings(sharedSettings*)
      Seq(root, api, core)
    }.use { ctx =>
      ReleaseStepIO.fromTaskAggregated(aggregatedStateTask).execute(ctx).map { result =>
        val base = SbtRuntime.extracted(result.state).get(Keys.baseDirectory)
        assertEquals(readFile(new File(base, "aggregated-task-root.txt")), "root")
        assertEquals(readFile(new File(base, "aggregated-task-api.txt")), "api")
        assertEquals(readFile(new File(base, "aggregated-task-core.txt")), "core")
      }
    }
  }

  private val contextResource: Resource[IO, ReleaseContext] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("sbt-release-io-compose-spec").toFile))(dir =>
        IO.blocking(TestSupport.deleteRecursively(dir))
      )
      .map(dir => ReleaseContext(state = TestSupport.dummyState(dir)))

  private def loadedContextResource(
      prefix: String,
      configure: Project => Project
  ): Resource[IO, ReleaseContext] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val project = configure(Project("root", dir))
        ReleaseContext(
          state = TestSupport.loadedState(
            dir,
            Seq(project),
            currentProjectId = Some(project.id)
          )
        )
      }
    }

  private def loadedContextWithProjectsResource(
      prefix: String
  )(projectsFor: File => Seq[Project]): Resource[IO, ReleaseContext] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        ReleaseContext(
          state = TestSupport.loadedState(
            dir,
            projectsFor(dir),
            currentProjectId = Some("root")
          )
        )
      }
    }

  private def scalaVersionOf(state: State): IO[String] =
    IO.blocking(SbtRuntime.extracted(state).get(Keys.scalaVersion))

  private def readFile(file: File): String =
    sbt.IO.read(file, StandardCharsets.UTF_8)

  private val stateUpdateTask          = taskKey[String]("stateUpdateTask")
  private val stateUpdateInputTask     = inputKey[String]("stateUpdateInputTask")
  private val aggregatedStateTask      = taskKey[String]("aggregatedStateTask")
}
