package io.release

import cats.effect.IO
import cats.effect.Ref
import io.release.internal.CoreStepFactory
import io.release.internal.ProcessStep
import io.release.internal.SbtRuntime
import munit.CatsEffectSuite
import sbt.AttributeKey
import sbt.Keys
import sbt.taskKey

class ReleaseStepIOCrossBuildSpec extends CatsEffectSuite with ReleaseStepIOSpecSupport {

  test("compose - cross-build logs include each configured Scala version") {
    TestSupport.tempDirResource("release-step-io-cross-log").use { dir =>
      IO.blocking {
        val buffered = TestSupport.bufferedState(dir)
        val state    = sbt.TestBuildState(
          baseState = buffered.state,
          baseDir = dir,
          projects = Seq(
            sbt
              .Project("root", dir)
              .settings(
                Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
                Keys.crossScalaVersions := Seq(
                  TestSupport.CurrentScalaVersion,
                  TestSupport.alternateScalaVersion
                )
              )
          ),
          currentProjectId = Some("root")
        )
        (ReleaseContext(state = state), buffered.consoleBuffer)
      }.flatMap { case (ctx, consoleBuffer) =>
        val step = ProcessStep.Single[ReleaseContext](
          name = "cross-step",
          execute = currentCtx => IO.pure(currentCtx),
          enableCrossBuild = true
        )

        ReleaseComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { _ =>
          IO.blocking {
            val log = consoleBuffer.toString("UTF-8")
            assert(
              log.contains(
                s"Cross-building with Scala ${TestSupport.CurrentScalaVersion}"
              )
            )
            assert(
              log.contains(
                s"Cross-building with Scala ${TestSupport.alternateScalaVersion}"
              )
            )
          }
        }
      }
    }
  }

  test(
    "compose - cross-build step runs once for a single configured Scala version and restores the entry version"
  ) {
    loadedContextResource(
      "release-step-io-single-cross",
      _.settings(
        Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Seq(TestSupport.alternateScalaVersion)
      )
    ).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val step = ProcessStep.Single[ReleaseContext](
          name = "cross-step",
          execute =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"execute:$v").as(c)),
          validate =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"validate:$v")),
          enableCrossBuild = true
        )

        ReleaseComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
          for {
            events       <- observed.get
            finalVersion <- scalaVersionOf(result.state)
          } yield {
            assertEquals(
              events,
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

  test(
    "compose - cross-build step runs for each configured Scala version while non-cross steps still run once"
  ) {
    loadedContextResource(
      "release-step-io-multi-cross",
      _.settings(
        Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Seq(
          TestSupport.CurrentScalaVersion,
          TestSupport.alternateScalaVersion
        )
      )
    ).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val crossStep = ProcessStep.Single[ReleaseContext](
          name = "cross-step",
          execute =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"execute:$v").as(c)),
          validate =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"validate:$v")),
          enableCrossBuild = true
        )
        val plainStep = CoreStepFactory.io("plain-step") { c =>
          scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"plain:$v").as(c))
        }

        ReleaseComposer.compose(Seq(crossStep, plainStep), crossBuild = true)(ctx).flatMap {
          result =>
            for {
              events       <- observed.get
              finalVersion <- scalaVersionOf(result.state)
            } yield {
              assertEquals(
                events,
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

  test(
    "compose - cross-build deduplicates configured Scala versions while preserving order"
  ) {
    loadedContextResource(
      "release-step-io-dedup-cross",
      _.settings(
        Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Seq(
          TestSupport.CurrentScalaVersion,
          TestSupport.alternateScalaVersion,
          TestSupport.CurrentScalaVersion
        )
      )
    ).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val crossStep = ProcessStep.Single[ReleaseContext](
          name = "cross-step",
          execute =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"execute:$v").as(c)),
          validate =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"validate:$v")),
          enableCrossBuild = true
        )
        val plainStep = CoreStepFactory.io("plain-step") { c =>
          scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"plain:$v").as(c))
        }

        ReleaseComposer.compose(Seq(crossStep, plainStep), crossBuild = true)(ctx).flatMap {
          result =>
            for {
              events       <- observed.get
              finalVersion <- scalaVersionOf(result.state)
            } yield {
              assertEquals(
                events,
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

  test("compose - restore the entry state when no entry scalaVersion is defined") {
    val metadataKey = AttributeKey[String]("cross-build-no-entry-scala-version")

    loadedContextResource(
      "release-step-io-no-entry-scala-version",
      _.settings(
        Keys.crossScalaVersions := Seq(
          TestSupport.CurrentScalaVersion,
          TestSupport.alternateScalaVersion
        )
      )
    ).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val crossStep = ProcessStep.Single[ReleaseContext](
          name = "cross-step",
          validate =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"cross-validate:$v")),
          execute = c =>
            scalaVersionOf(c.state).flatMap(v =>
              observed.update(_ :+ s"cross-execute:$v").as(c.withMetadata(metadataKey, "kept"))
            ),
          enableCrossBuild = true
        )
        val plainStep = ProcessStep.Single[ReleaseContext](
          name = "plain-step",
          validate = c =>
            scopedScalaVersionOf(c.state)
              .flatMap(v => observed.update(_ :+ s"plain-validate:$v")),
          execute = c =>
            scopedScalaVersionOf(c.state).flatMap { v =>
              if (c.metadata(metadataKey).contains("kept"))
                observed.update(_ :+ s"plain-execute:$v").as(c)
              else
                IO.raiseError(new RuntimeException("missing metadata after restore"))
            }
        )

        scopedScalaVersionOf(ctx.state).flatMap { initialVersion =>
          ReleaseComposer.compose(Seq(crossStep, plainStep), crossBuild = true)(ctx).flatMap {
            result =>
              for {
                events          <- observed.get
                restoredVersion <- scopedScalaVersionOf(result.state)
              } yield {
                assertEquals(initialVersion, None)
                assertEquals(restoredVersion, None)
                assertEquals(
                  events,
                  List(
                    s"cross-validate:${TestSupport.CurrentScalaVersion}",
                    s"cross-validate:${TestSupport.alternateScalaVersion}",
                    "plain-validate:None",
                    s"cross-execute:${TestSupport.CurrentScalaVersion}",
                    s"cross-execute:${TestSupport.alternateScalaVersion}",
                    "plain-execute:None"
                  )
                )
                assertEquals(result.metadata(metadataKey), Some("kept"))
              }
          }
        }
      }
    }
  }

  test("compose - fail validation when cross-build is enabled with no configured cross versions") {
    loadedContextResource(
      "release-step-io-empty-cross",
      _.settings(
        Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Nil
      )
    ).use { ctx =>
      Ref.of[IO, Boolean](false).flatMap { executed =>
        val step = ProcessStep.Single[ReleaseContext](
          name = "cross-step",
          execute = c => executed.set(true).as(c),
          enableCrossBuild = true
        )

        ReleaseComposer.compose(Seq(step), crossBuild = true)(ctx).attempt.flatMap { result =>
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
        Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Seq(
          TestSupport.CurrentScalaVersion,
          TestSupport.alternateScalaVersion
        )
      )
    ).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val step = ProcessStep
          .single[ReleaseContext]("cross-step")
          .withCrossBuild
          .execute { c =>
            scalaVersionOf(c.state).flatMap { version =>
              observed.update(_ :+ s"execute:$version") *>
                (if (version == TestSupport.alternateScalaVersion)
                   IO.raiseError(new RuntimeException("boom"))
                 else IO.pure(c))
            }
          }

        ReleaseComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
          for {
            events       <- observed.get
            finalVersion <- scalaVersionOf(result.state)
          } yield {
            assert(result.failed)
            assertEquals(
              events,
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

  test(
    "compose - cross-build task step short-circuits remaining versions when a version reports FailureCommand"
  ) {
    val failureCommandTask = taskKey[Unit](s"failureCommandCrossBuildTask${System.nanoTime()}")

    loadedContextResource(
      "release-step-io-cross-failure-command",
      root =>
        root.settings(
          Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
          Keys.crossScalaVersions := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          ),
          ReleaseStepIOCrossBuildCompat.failureCommandTaskSetting(
            failureCommandTask,
            new java.io.File(root.base, "failure-command-cross.txt")
          )
        )
    ).use { ctx =>
      val step = CoreStepFactory.fromTask(failureCommandTask, enableCrossBuild = true)

      ReleaseComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
        val marker =
          new java.io.File(
            SbtRuntime.extracted(result.state).get(Keys.baseDirectory),
            "failure-command-cross.txt"
          )

        readFile(marker).flatMap { contents =>
          scalaVersionOf(result.state).map { finalVersion =>
            val executedVersions = contents.split('\n').toList.filter(_.nonEmpty)
            assert(result.failed)
            assertEquals(executedVersions, List(TestSupport.CurrentScalaVersion))
            assert(
              result.failureCause
                .exists(_.getMessage.contains("reported failure via FailureCommand"))
            )
            assertEquals(finalVersion, TestSupport.CurrentScalaVersion)
          }
        }
      }
    }
  }

  test(
    "compose - cross-build step short-circuits remaining versions when a version fails via context"
  ) {
    loadedContextResource(
      "release-step-io-cross-short-circuit",
      _.settings(
        Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Seq(
          TestSupport.CurrentScalaVersion,
          TestSupport.alternateScalaVersion
        )
      )
    ).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val step = ProcessStep.Single[ReleaseContext](
          name = "cross-step",
          execute = c =>
            scalaVersionOf(c.state).flatMap { version =>
              observed.update(_ :+ s"execute:$version").as {
                if (version == TestSupport.CurrentScalaVersion)
                  c.failWith(new IllegalStateException("simulated task failure"))
                else c
              }
            },
          enableCrossBuild = true
        )

        ReleaseComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
          observed.get.map { events =>
            assert(result.failed)
            assertEquals(events, List(s"execute:${TestSupport.CurrentScalaVersion}"))
          }
        }
      }
    }
  }

  test("compose - single-version cross-build restores entry Scala version after execute throws") {
    loadedContextResource(
      "release-step-io-single-cross-failure",
      _.settings(
        Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Seq(TestSupport.alternateScalaVersion)
      )
    ).use { ctx =>
      val step = ProcessStep
        .single[ReleaseContext]("cross-step")
        .withCrossBuild
        .execute(_ => IO.raiseError(new RuntimeException("boom")))

      ReleaseComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
        scalaVersionOf(result.state).map { finalVersion =>
          assert(result.failed)
          assert(result.failureCause.exists(_.getMessage.contains("boom")))
          assertEquals(finalVersion, TestSupport.CurrentScalaVersion)
        }
      }
    }
  }

  test("compose - cross-build short-circuit restores entry Scala version") {
    loadedContextResource(
      "release-step-io-cross-short-circuit-restore",
      _.settings(
        Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Seq(
          TestSupport.alternateScalaVersion,
          TestSupport.CurrentScalaVersion
        )
      )
    ).use { ctx =>
      val step = ProcessStep.Single[ReleaseContext](
        name = "cross-step",
        execute = c => IO.pure(c.failWith(new IllegalStateException("fail on first version"))),
        enableCrossBuild = true
      )

      ReleaseComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
        scalaVersionOf(result.state).map { finalVersion =>
          assert(result.failed)
          assertEquals(finalVersion, TestSupport.CurrentScalaVersion)
        }
      }
    }
  }

  test("compose - cross validation runs per-version while non-cross validation runs once") {
    loadedContextResource(
      "release-step-io-cross-validation-mix",
      _.settings(
        Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Seq(
          TestSupport.CurrentScalaVersion,
          TestSupport.alternateScalaVersion
        )
      )
    ).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val crossStep = ProcessStep.Single[ReleaseContext](
          name = "cross-step",
          execute = c => IO.pure(c),
          validate =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"cross-validate:$v")),
          enableCrossBuild = true
        )
        val plainStep = ProcessStep.Single[ReleaseContext](
          name = "plain-step",
          execute = c => IO.pure(c),
          validate =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"plain-validate:$v"))
        )

        ReleaseComposer.compose(Seq(crossStep, plainStep), crossBuild = true)(ctx).flatMap { _ =>
          observed.get.map { events =>
            assertEquals(
              events,
              List(
                s"cross-validate:${TestSupport.CurrentScalaVersion}",
                s"cross-validate:${TestSupport.alternateScalaVersion}",
                s"plain-validate:${TestSupport.CurrentScalaVersion}"
              )
            )
          }
        }
      }
    }
  }

  test("compose - two sequential cross-build steps each iterate all versions independently") {
    loadedContextResource(
      "release-step-io-sequential-cross",
      _.settings(
        Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Seq(
          TestSupport.CurrentScalaVersion,
          TestSupport.alternateScalaVersion
        )
      )
    ).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val step1 = ProcessStep.Single[ReleaseContext](
          name = "cross-step-1",
          execute =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"step1:$v").as(c)),
          enableCrossBuild = true
        )
        val step2 = ProcessStep.Single[ReleaseContext](
          name = "cross-step-2",
          execute =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"step2:$v").as(c)),
          enableCrossBuild = true
        )

        ReleaseComposer.compose(Seq(step1, step2), crossBuild = true)(ctx).flatMap { result =>
          for {
            events       <- observed.get
            finalVersion <- scalaVersionOf(result.state)
          } yield {
            assertEquals(
              events,
              List(
                s"step1:${TestSupport.CurrentScalaVersion}",
                s"step1:${TestSupport.alternateScalaVersion}",
                s"step2:${TestSupport.CurrentScalaVersion}",
                s"step2:${TestSupport.alternateScalaVersion}"
              )
            )
            assertEquals(finalVersion, TestSupport.CurrentScalaVersion)
          }
        }
      }
    }
  }
}
