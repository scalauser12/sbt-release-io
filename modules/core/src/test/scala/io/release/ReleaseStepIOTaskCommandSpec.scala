package io.release

import io.release.TestAssertions.assertFailure
import io.release.internal.SbtRuntime
import munit.CatsEffectSuite
import sbt.Def._
import sbt.Keys
import sbt.LocalProject
import sbt.Project
import sbt.taskKey

import java.io.File

@scala.annotation.nowarn("cat=deprecation")
class ReleaseStepIOTaskCommandSpec extends CatsEffectSuite with ReleaseStepIOSpecSupport {

  test("command steps - surface command parse failures for fromCommand") {
    contextResource.use { ctx =>
      assertFailure[RuntimeException, ReleaseContext](
        ReleaseStepIO.fromCommand("this-command-does-not-exist").execute(ctx)
      )(err => assert(err.getMessage.contains("Failed to parse command")))
    }
  }

  test("command steps - surface command parse failures for fromCommandAndRemaining") {
    contextResource.use { ctx =>
      assertFailure[RuntimeException, ReleaseContext](
        ReleaseStepIO.fromCommandAndRemaining("this-command-does-not-exist").execute(ctx)
      )(err => assert(err.getMessage.contains("Failed to parse command")))
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
      ReleaseStepIO.fromTask(stateUpdateTask).execute(ctx).flatMap { result =>
        val marker =
          new File(SbtRuntime.extracted(result.state).get(Keys.baseDirectory), "from-task.txt")
        readFile(marker).map(content => assertEquals(content, "task-ran"))
      }
    }
  }

  test("task steps - run fromInputTask with explicit args and thread back the updated state") {
    loadedContextResource(
      "release-step-io-from-input-task",
      _.settings(
        stateUpdateInputTask := {
          val parsed = spaceDelimited("<arg>").parsed.mkString(":")
          val marker = new File(Keys.baseDirectory.value, "from-input-task.txt")
          sbt.IO.write(marker, parsed)
          parsed
        }
      )
    ).use { ctx =>
      ReleaseStepIO
        .fromInputTask(stateUpdateInputTask, args = " alpha beta")
        .execute(ctx)
        .flatMap { result =>
          val marker =
            new File(
              SbtRuntime.extracted(result.state).get(Keys.baseDirectory),
              "from-input-task.txt"
            )
          readFile(marker).map(content => assertEquals(content, "alpha:beta"))
        }
    }
  }

  test("task steps - run fromTaskAggregated across aggregated projects") {
    val aggregatedTask = taskKey[String](s"aggregatedStateTask${System.nanoTime()}")

    loadedContextWithProjectsResource("release-step-io-from-task-aggregated") { dir =>
      val apiBase  = new File(dir, "api")
      val coreBase = new File(dir, "core")
      apiBase.mkdirs()
      coreBase.mkdirs()

      val api  = Project("api", apiBase).settings(
        aggregatedTask := {
          val marker = new File(Keys.baseDirectory.value, "aggregated-task-api.txt")
          sbt.IO.write(marker, "api")
          "api"
        }
      )
      val core = Project("core", coreBase).settings(
        aggregatedTask := {
          val marker = new File(Keys.baseDirectory.value, "aggregated-task-core.txt")
          sbt.IO.write(marker, "core")
          "core"
        }
      )
      val root = Project("root", dir)
        .aggregate(LocalProject("api"), LocalProject("core"))
        .settings(
          aggregatedTask / Keys.aggregate := true,
          aggregatedTask                  := {
            val marker = new File(Keys.baseDirectory.value, "aggregated-task-root.txt")
            sbt.IO.write(marker, "root")
            "root"
          }
        )

      Seq(root, api, core)
    }.use { ctx =>
      ReleaseStepIO.fromTaskAggregated(aggregatedTask).execute(ctx).flatMap { result =>
        val extracted = SbtRuntime.extracted(result.state)

        for {
          root <- readFile(new File(extracted.get(Keys.baseDirectory), "aggregated-task-root.txt"))
          api  <- readFile(
                    new File(
                      extracted.get(LocalProject("api") / Keys.baseDirectory),
                      "aggregated-task-api.txt"
                    )
                  )
          core <- readFile(
                    new File(
                      extracted.get(LocalProject("core") / Keys.baseDirectory),
                      "aggregated-task-core.txt"
                    )
                  )
        } yield {
          assertEquals(root, "root")
          assertEquals(api, "api")
          assertEquals(core, "core")
        }
      }
    }
  }

  test("task steps - honor per-task aggregate := false in fromTaskAggregated") {
    val aggregatedTask = taskKey[String](s"nonAggregatedStateTask${System.nanoTime()}")

    loadedContextWithProjectsResource("release-step-io-from-task-aggregate-false") { dir =>
      val apiBase  = new File(dir, "api")
      val coreBase = new File(dir, "core")
      apiBase.mkdirs()
      coreBase.mkdirs()

      val api  = Project("api", apiBase).settings(
        aggregatedTask := {
          val marker = new File(Keys.baseDirectory.value, "aggregated-task-api.txt")
          sbt.IO.write(marker, "api")
          "api"
        }
      )
      val core = Project("core", coreBase).settings(
        aggregatedTask := {
          val marker = new File(Keys.baseDirectory.value, "aggregated-task-core.txt")
          sbt.IO.write(marker, "core")
          "core"
        }
      )
      val root = Project("root", dir)
        .aggregate(LocalProject("api"), LocalProject("core"))
        .settings(
          aggregatedTask / Keys.aggregate := false,
          aggregatedTask                  := {
            val marker = new File(Keys.baseDirectory.value, "aggregated-task-root.txt")
            sbt.IO.write(marker, "root")
            "root"
          }
        )

      Seq(root, api, core)
    }.use { ctx =>
      ReleaseStepIO.fromTaskAggregated(aggregatedTask).execute(ctx).flatMap { result =>
        val extracted = SbtRuntime.extracted(result.state)

        readFile(new File(extracted.get(Keys.baseDirectory), "aggregated-task-root.txt")).map {
          root =>
            assertEquals(root, "root")
            assert(
              !new File(
                extracted.get(LocalProject("api") / Keys.baseDirectory),
                "aggregated-task-api.txt"
              ).exists()
            )
            assert(
              !new File(
                extracted.get(LocalProject("core") / Keys.baseDirectory),
                "aggregated-task-core.txt"
              ).exists()
            )
        }
      }
    }
  }
}
