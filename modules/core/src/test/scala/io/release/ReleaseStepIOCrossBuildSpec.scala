package io.release

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite
import sbt.Keys

class ReleaseStepIOCrossBuildSpec extends CatsEffectSuite with ReleaseStepIOSpecSupport {

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
        val step = ReleaseStepIO(
          name = "cross-step",
          execute =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"execute:$v").as(c)),
          validate =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"validate:$v")),
          enableCrossBuild = true
        )

        ReleaseStepIO.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
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
        val crossStep = ReleaseStepIO(
          name = "cross-step",
          execute =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"execute:$v").as(c)),
          validate =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"validate:$v")),
          enableCrossBuild = true
        )
        val plainStep = ReleaseStepIO.io("plain-step") { c =>
          scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"plain:$v").as(c))
        }

        ReleaseStepIO.compose(Seq(crossStep, plainStep), crossBuild = true)(ctx).flatMap {
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

  test("compose - fail validation when cross-build is enabled with no configured cross versions") {
    loadedContextResource(
      "release-step-io-empty-cross",
      _.settings(
        Keys.scalaVersion       := TestSupport.CurrentScalaVersion,
        Keys.crossScalaVersions := Nil
      )
    ).use { ctx =>
      Ref.of[IO, Boolean](false).flatMap { executed =>
        val step = ReleaseStepIO(
          name = "cross-step",
          execute = c => executed.set(true).as(c),
          enableCrossBuild = true
        )

        ReleaseStepIO.compose(Seq(step), crossBuild = true)(ctx).attempt.flatMap { result =>
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
        val step = ReleaseStepIO
          .io("cross-step") { c =>
            scalaVersionOf(c.state).flatMap { version =>
              observed.update(_ :+ s"execute:$version") *>
                (if (version == TestSupport.alternateScalaVersion)
                   IO.raiseError(new RuntimeException("boom"))
                 else IO.pure(c))
            }
          }
          .copy(enableCrossBuild = true)

        ReleaseStepIO.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
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
        val step = ReleaseStepIO(
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

        ReleaseStepIO.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
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
      val step = ReleaseStepIO
        .io("cross-step")(_ => IO.raiseError(new RuntimeException("boom")))
        .copy(enableCrossBuild = true)

      ReleaseStepIO.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
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
      val step = ReleaseStepIO(
        name = "cross-step",
        execute = c => IO.pure(c.failWith(new IllegalStateException("fail on first version"))),
        enableCrossBuild = true
      )

      ReleaseStepIO.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
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
        val crossStep = ReleaseStepIO(
          name = "cross-step",
          execute = c => IO.pure(c),
          validate =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"cross-validate:$v")),
          enableCrossBuild = true
        )
        val plainStep = ReleaseStepIO(
          name = "plain-step",
          execute = c => IO.pure(c),
          validate =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"plain-validate:$v"))
        )

        ReleaseStepIO.compose(Seq(crossStep, plainStep), crossBuild = true)(ctx).flatMap { _ =>
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
        val step1 = ReleaseStepIO(
          name = "cross-step-1",
          execute =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"step1:$v").as(c)),
          enableCrossBuild = true
        )
        val step2 = ReleaseStepIO(
          name = "cross-step-2",
          execute =
            c => scalaVersionOf(c.state).flatMap(v => observed.update(_ :+ s"step2:$v").as(c)),
          enableCrossBuild = true
        )

        ReleaseStepIO.compose(Seq(step1, step2), crossBuild = true)(ctx).flatMap { result =>
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
