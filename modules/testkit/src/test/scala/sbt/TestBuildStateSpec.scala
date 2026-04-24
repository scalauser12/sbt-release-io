package sbt

import _root_.io.release.TestSupport
import _root_.io.release.TestkitSbtCompat
import cats.effect.IO as CEIO
import cats.effect.Resource
import munit.CatsEffectSuite

import java.io.File as JFile
import java.nio.charset.StandardCharsets

class TestBuildStateSpec extends CatsEffectSuite {

  test("synthetic loaded state - expose the current project and base directory") {
    singleProjectStateResource.use { state =>
      CEIO.blocking {
        val extracted = TestkitSbtCompat.extract(state)
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
        val base              = TestkitSbtCompat.extract(state).get(Keys.baseDirectory)
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
        val appendedExtracted = TestkitSbtCompat.extract(seeded)
        val (newState, value) = appendedExtracted.runTask(appendedTask, seeded)

        assertEquals(value, "appended")
        assertEquals(
          sbt.IO.read(new JFile(base, "appended-task.txt"), StandardCharsets.UTF_8),
          "appended"
        )
        assertEquals(TestkitSbtCompat.extract(newState).currentRef.project, "root")
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
        val extracted = TestkitSbtCompat.extract(state)
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
        assertEquals(TestkitSbtCompat.extract(newState).currentRef.project, "root")
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
        val extracted = TestkitSbtCompat.extract(state)
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
        assertEquals(TestkitSbtCompat.extract(newState).currentRef.project, "root")
      }
    }
  }

  test("synthetic loaded state - reject non-local RootProject aggregate references") {
    assertRejectsNonLocalRootProject("test-build-state-non-local-aggregate") { (dir, foreignUri) =>
      Seq(Project("root", dir).aggregate(RootProject(foreignUri)))
    }
  }

  test("synthetic loaded state - reject non-local RootProject dependency references") {
    assertRejectsNonLocalRootProject("test-build-state-non-local-dependency") { (dir, foreignUri) =>
      Seq(
        Project("root", dir).dependsOn(
          ClasspathDependency(RootProject(foreignUri), None)
        )
      )
    }
  }

  test("synthetic loaded state - reject unknown LocalProject id in aggregate") {
    TestSupport.tempDirResource("test-build-state-unknown-local-aggregate").use { dir =>
      CEIO.blocking {
        val error = intercept[IllegalArgumentException] {
          TestSupport.loadedState(
            dir,
            Seq(Project("root", dir).aggregate(LocalProject("ghost"))),
            currentProjectId = Some("root")
          )
        }
        assert(
          error.getMessage.startsWith("Unknown LocalProject id 'ghost'"),
          s"unexpected message: ${error.getMessage}"
        )
      }
    }
  }

  test("synthetic loaded state - reject unknown LocalProject id in dependsOn") {
    TestSupport.tempDirResource("test-build-state-unknown-local-dependency").use { dir =>
      CEIO.blocking {
        val error = intercept[IllegalArgumentException] {
          TestSupport.loadedState(
            dir,
            Seq(
              Project("root", dir).dependsOn(
                ClasspathDependency(LocalProject("ghost"), None)
              )
            ),
            currentProjectId = Some("root")
          )
        }
        assert(
          error.getMessage.startsWith("Unknown LocalProject id 'ghost'"),
          s"unexpected message: ${error.getMessage}"
        )
      }
    }
  }

  test("synthetic loaded state - reject unknown ProjectRef id in aggregate") {
    TestSupport.tempDirResource("test-build-state-unknown-project-ref").use { dir =>
      CEIO.blocking {
        val localUri = dir.getCanonicalFile.toURI
        val error    = intercept[IllegalArgumentException] {
          TestSupport.loadedState(
            dir,
            Seq(Project("root", dir).aggregate(ProjectRef(localUri, "ghost"))),
            currentProjectId = Some("root")
          )
        }
        assert(
          error.getMessage.startsWith("Unknown project id 'ghost'"),
          s"unexpected message: ${error.getMessage}"
        )
      }
    }
  }

  test("synthetic loaded state - reject unknown currentProjectId") {
    TestSupport.tempDirResource("test-build-state-unknown-current-project").use { dir =>
      CEIO.blocking {
        val error = intercept[IllegalArgumentException] {
          TestSupport.loadedState(
            dir,
            Seq(Project("root", dir)),
            currentProjectId = Some("ghost")
          )
        }
        assert(
          error.getMessage.startsWith("Unknown currentProjectId 'ghost'"),
          s"unexpected message: ${error.getMessage}"
        )
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

  private def assertRejectsNonLocalRootProject(
      prefix: String
  )(projectsFor: (JFile, java.net.URI) => Seq[Project]): CEIO[Unit] =
    TestSupport.tempDirResource(prefix).use { dir =>
      CEIO.blocking {
        val foreignUri = java.net.URI.create("https://example.invalid/synthetic-foreign-root/")
        val error      = intercept[IllegalArgumentException] {
          TestSupport.loadedState(
            dir,
            projectsFor(dir, foreignUri),
            currentProjectId = Some("root")
          )
        }

        assertEquals(
          error.getMessage,
          s"Unsupported non-local RootProject reference in synthetic test state: $foreignUri"
        )
      }
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
