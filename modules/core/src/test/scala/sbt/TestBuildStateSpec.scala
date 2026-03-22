package sbt

import cats.effect.{IO as CEIO, Resource}
import munit.CatsEffectSuite

import _root_.io.release.TestSupport

import java.io.{File as JFile}
import java.nio.charset.StandardCharsets

class TestBuildStateSpec extends CatsEffectSuite {

  test("synthetic loaded state - expose the current project and base directory") {
    singleProjectStateResource.use { state =>
      CEIO.blocking {
        val extracted = Project.extract(state)
        assertEquals(extracted.currentRef.project, "root")
        assertEquals(
          extracted.get(Keys.baseDirectory).getCanonicalFile,
          state.configuration.baseDirectory.getCanonicalFile
        )
      }
    }
  }

  test("synthetic loaded state - appendWithSession makes appended task settings runnable") {
    val appendedTask = taskKey[String](s"appendedStateTask${System.nanoTime()}")

    singleProjectStateResource.use { state =>
      CEIO.blocking {
        val base              = Project.extract(state).get(Keys.baseDirectory)
        val seeded            = TestSupport.appendSessionSettings(
          state,
          Seq(
            appendedTask := {
              val marker = new JFile(Keys.baseDirectory.value, "appended-task.txt")
              sbt.IO.write(marker, "appended")
              "appended"
            }
          )
        )
        val appendedExtracted = Project.extract(seeded)
        val (newState, value) = appendedExtracted.runTask(appendedTask, seeded)

        assertEquals(value, "appended")
        assertEquals(
          sbt.IO.read(new JFile(base, "appended-task.txt"), StandardCharsets.UTF_8),
          "appended"
        )
        assertEquals(Project.extract(newState).currentRef.project, "root")
      }
    }
  }

  test("synthetic loaded state - runAggregated executes root and aggregated children") {
    val aggregatedTask = taskKey[String](s"aggregatedHarnessTask${System.nanoTime()}")

    multiProjectStateResource("test-build-state-aggregated") { dir =>
      val apiBase  = new JFile(dir, "api")
      val coreBase = new JFile(dir, "core")
      apiBase.mkdirs()
      coreBase.mkdirs()
      Seq(
        Project("root", dir)
          .aggregate(LocalProject("api"), LocalProject("core"))
          .settings(
            aggregatedTask / Keys.aggregate := true,
            aggregatedTask                  := writeMarker("root").value
          ),
        Project("api", apiBase).settings(
          aggregatedTask                    := writeMarker("api").value
        ),
        Project("core", coreBase).settings(
          aggregatedTask                    := writeMarker("core").value
        )
      )
    }.use { state =>
      CEIO.blocking {
        val extracted = Project.extract(state)
        extracted.get(Keys.baseDirectory)
        assertEquals(extracted.currentProject.aggregate.map(_.project).sorted, List("api", "core"))
        assertEquals(
          sbt.internal.Aggregation
            .projectAggregates(Some(extracted.currentRef), extracted.structure.extra, false)
            .map(_.project)
            .sorted,
          List("api", "core")
        )
        assertEquals(extracted.get(aggregatedTask / Keys.aggregate), true)
        assert(extracted.getOpt(aggregatedTask).isDefined)
        assert(extracted.getOpt(LocalProject("api") / aggregatedTask).isDefined)
        assert(extracted.getOpt(LocalProject("core") / aggregatedTask).isDefined)
        assert(
          sbt.internal.Aggregation.aggregationEnabled(
            (extracted.currentRef / aggregatedTask).scopedKey,
            extracted.structure.data
          )
        )
        val newState  =
          extracted.runAggregated(extracted.currentRef / aggregatedTask, state)
        assertEquals(
          readFile(new JFile(extracted.get(Keys.baseDirectory), "aggregated-task-root.txt")),
          "root"
        )
        assertEquals(
          readFile(
            new JFile(
              extracted.get(LocalProject("api") / Keys.baseDirectory),
              "aggregated-task-api.txt"
            )
          ),
          "api"
        )
        assertEquals(
          readFile(
            new JFile(
              extracted.get(LocalProject("core") / Keys.baseDirectory),
              "aggregated-task-core.txt"
            )
          ),
          "core"
        )
        assertEquals(Project.extract(newState).currentRef.project, "root")
      }
    }
  }

  test("synthetic loaded state - runAggregated honors per-task aggregate := false") {
    val aggregatedTask = taskKey[String](s"aggregateFalseHarnessTask${System.nanoTime()}")

    multiProjectStateResource("test-build-state-aggregate-false") { dir =>
      val apiBase  = new JFile(dir, "api")
      val coreBase = new JFile(dir, "core")
      apiBase.mkdirs()
      coreBase.mkdirs()
      Seq(
        Project("root", dir)
          .aggregate(LocalProject("api"), LocalProject("core"))
          .settings(
            aggregatedTask / Keys.aggregate := false,
            aggregatedTask                  := writeMarker("root").value
          ),
        Project("api", apiBase).settings(
          aggregatedTask                    := writeMarker("api").value
        ),
        Project("core", coreBase).settings(
          aggregatedTask                    := writeMarker("core").value
        )
      )
    }.use { state =>
      CEIO.blocking {
        val extracted = Project.extract(state)
        extracted.get(Keys.baseDirectory)
        assertEquals(extracted.currentProject.aggregate.map(_.project).sorted, List("api", "core"))
        assertEquals(
          sbt.internal.Aggregation
            .projectAggregates(Some(extracted.currentRef), extracted.structure.extra, false)
            .map(_.project)
            .sorted,
          List("api", "core")
        )
        assertEquals(extracted.get(aggregatedTask / Keys.aggregate), false)
        assert(extracted.getOpt(aggregatedTask).isDefined)
        assert(extracted.getOpt(LocalProject("api") / aggregatedTask).isDefined)
        assert(extracted.getOpt(LocalProject("core") / aggregatedTask).isDefined)
        assert(
          !sbt.internal.Aggregation.aggregationEnabled(
            (extracted.currentRef / aggregatedTask).scopedKey,
            extracted.structure.data
          )
        )
        val newState  =
          extracted.runAggregated(extracted.currentRef / aggregatedTask, state)
        val rootBase  = extracted.get(Keys.baseDirectory)
        val apiBase   = extracted.get(LocalProject("api") / Keys.baseDirectory)
        val coreBase  = extracted.get(LocalProject("core") / Keys.baseDirectory)
        assertEquals(readFile(new JFile(rootBase, "aggregated-task-root.txt")), "root")
        assert(!new JFile(apiBase, "aggregated-task-api.txt").exists())
        assert(!new JFile(coreBase, "aggregated-task-core.txt").exists())
        assertEquals(Project.extract(newState).currentRef.project, "root")
      }
    }
  }

  private val singleProjectStateResource: Resource[CEIO, State] =
    TestSupport.tempDirResource("test-build-state-single").evalMap { dir =>
      CEIO.blocking(
        TestSupport.loadedState(
          dir,
          Seq(Project("root", dir)),
          currentProjectId = Some("root")
        )
      )
    }

  private def multiProjectStateResource(
      prefix: String
  )(projectsFor: JFile => Seq[Project]): Resource[CEIO, State] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      CEIO.blocking(
        TestSupport.loadedState(
          dir,
          projectsFor(dir),
          currentProjectId = Some("root")
        )
      )
    }

  private def readFile(file: JFile): String =
    sbt.IO.read(file, StandardCharsets.UTF_8)

  private def writeMarker(label: String): Def.Initialize[Task[String]] =
    Def.task {
      val marker = new JFile(Keys.baseDirectory.value, s"aggregated-task-$label.txt")
      sbt.IO.write(marker, label)
      label
    }
}
