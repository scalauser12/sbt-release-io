package io.release.runtime.sbt

import cats.effect.IO
import cats.effect.Resource
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.*

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class SbtRuntimeSelectiveAggregationSpec extends CatsEffectSuite {

  private val fixturePrefix = "sbt-runtime-selective-aggregation-spec"

  test("runTaskAggregatedForProjects - exclude aggregate projects outside the selected set") {
    val action    = taskKey[Unit](s"selectiveAction${System.nanoTime()}")
    val rootRuns  = new AtomicInteger(0)
    val childRuns = new AtomicInteger(0)

    twoProjectState(
      s"$fixturePrefix-exclusion",
      rootSettings = Seq(
        SbtRuntimeSelectiveAggregationTestCompat.countedTaskSetting(action, rootRuns)
      ),
      childSettings = Seq(
        SbtRuntimeSelectiveAggregationTestCompat.countedTaskSetting(action, childRuns)
      )
    ).use { state =>
      val refs = refsById(state)

      IO.blocking(
        SbtRuntime.runTaskAggregatedForProjects(state, action, Seq(refs("child")))
      ).map { _ =>
        assertEquals(rootRuns.get(), 0)
        assertEquals(childRuns.get(), 1)
      }
    }
  }

  test(
    "runTaskAggregatedForProjects - run duplicate project roots once and deduplicate shared tasks"
  ) {
    val action     = taskKey[Unit](s"selectiveSharedAction${System.nanoTime()}")
    val shared     = taskKey[Unit](s"selectiveSharedDependency${System.nanoTime()}")
    val rootRuns   = new AtomicInteger(0)
    val childRuns  = new AtomicInteger(0)
    val sharedRuns = new AtomicInteger(0)

    twoProjectState(
      s"$fixturePrefix-shared",
      rootSettings = Seq(
        SbtRuntimeSelectiveAggregationTestCompat.globalCountedTaskSetting(shared, sharedRuns),
        SbtRuntimeSelectiveAggregationTestCompat.countedTaskWithDependencySetting(
          action,
          shared,
          rootRuns
        )
      ),
      childSettings = Seq(
        SbtRuntimeSelectiveAggregationTestCompat.countedTaskWithDependencySetting(
          action,
          shared,
          childRuns
        )
      )
    ).use { state =>
      val refs     = refsById(state)
      val selected = Seq(refs("child"), refs("root"), refs("child"), refs("root"))

      IO.blocking(SbtRuntime.runTaskAggregatedForProjects(state, action, selected)).map { _ =>
        assertEquals(rootRuns.get(), 1)
        assertEquals(childRuns.get(), 1)
        assertEquals(
          sharedRuns.get(),
          1,
          "selected actions must share one Aggregation.runTasks dependency graph"
        )
      }
    }
  }

  test("runTaskAggregatedForProjects - fail before execution when a selected action is missing") {
    val action   = taskKey[Unit](s"selectiveMissingAction${System.nanoTime()}")
    val rootRuns = new AtomicInteger(0)

    twoProjectState(
      s"$fixturePrefix-missing",
      rootSettings = Seq(
        SbtRuntimeSelectiveAggregationTestCompat.countedTaskSetting(action, rootRuns)
      ),
      childSettings = Seq.empty
    ).use { state =>
      val refs = refsById(state)

      IO.blocking {
        val error = intercept[IllegalStateException] {
          SbtRuntime.runTaskAggregatedForProjects(
            state,
            action,
            Seq(refs("root"), refs("child"))
          )
        }

        assert(error.getMessage.contains(action.key.label))
        assert(error.getMessage.contains("child"))
        assertEquals(rootRuns.get(), 0, "no selected action may run before resolution succeeds")
      }
    }
  }

  test("runTaskAggregatedForProjects - return the original state for an empty selection") {
    val action = taskKey[Unit](s"selectiveEmptyAction${System.nanoTime()}")

    TestSupport.dummyStateResource(s"$fixturePrefix-empty").use { state =>
      IO.blocking {
        val result = SbtRuntime.runTaskAggregatedForProjects(state, action, Seq.empty)
        assert(result eq state)
      }
    }
  }

  private def twoProjectState(
      prefix: String,
      rootSettings: Seq[Setting[?]],
      childSettings: Seq[Setting[?]]
  ): Resource[IO, State] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val childBase = new File(dir, "child")
        childBase.mkdirs()

        TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir)
              .aggregate(LocalProject("child"))
              .settings(rootSettings*),
            Project("child", childBase).settings(childSettings*)
          ),
          currentProjectId = Some("root")
        )
      }
    }

  private def refsById(state: State): Map[String, ProjectRef] =
    SbtRuntime
      .extracted(state)
      .structure
      .allProjectRefs
      .map(ref => ref.project -> ref)
      .toMap

}
