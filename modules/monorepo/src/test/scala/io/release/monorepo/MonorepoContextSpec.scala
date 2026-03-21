package io.release.monorepo

import io.release.internal.ExecutionFlags
import io.release.TestSupport
import munit.FunSuite
import sbt.{AttributeKey, State}

import java.nio.file.Files

class MonorepoContextSpec extends FunSuite {

  test("MonorepoContext - update a specific project") {
    withState { state =>
      val projects = Seq(dummyProject("core"), dummyProject("api"))
      val ctx      = MonorepoContext(state = state, projects = projects)
      val updated  =
        ctx.updateProject(projects(0).ref)(
          _.copy(versions = Some(("1.0.0", "1.1.0-SNAPSHOT")))
        )

      assertEquals(updated.projects(0).versions, Some(("1.0.0", "1.1.0-SNAPSHOT")))
      assertEquals(updated.projects(1).versions, None)
    }
  }

  test("MonorepoContext - filter out failed projects in currentProjects") {
    withState { state =>
      val projects = Seq(
        dummyProject("core").copy(failed = true),
        dummyProject("api")
      )
      val ctx      = MonorepoContext(state = state, projects = projects)

      assertEquals(ctx.currentProjects.map(_.name), Seq("api"))
    }
  }

  test("MonorepoContext - manage typed metadata") {
    withState { state =>
      val ctx     = MonorepoContext(state = state)
      val key1    = AttributeKey[String]("key1")
      val key2    = AttributeKey[Int]("key2")
      val updated = ctx.withMetadata(key1, "val1").withMetadata(key2, 2)
      val removed = updated.withoutMetadata(key1)

      assertEquals(updated.metadata(key1), Some("val1"))
      assertEquals(updated.metadata(key2), Some(2))
      assertEquals(removed.metadata(key1), None)
      assertEquals(removed.metadata(key2), Some(2))
    }
  }

  test("MonorepoContext - mark as failed") {
    withState { state =>
      val ctx = MonorepoContext(state = state)
      assertEquals(ctx.failed, false)
      assertEquals(ctx.fail.failed, true)
    }
  }

  test("MonorepoContext - replace projects via withProjects") {
    withState { state =>
      val ctx     = MonorepoContext(state = state, projects = Seq(dummyProject("old")))
      val updated = ctx.withProjects(Seq(dummyProject("new1"), dummyProject("new2")))

      assertEquals(updated.projects.map(_.name), Seq("new1", "new2"))
    }
  }

  test("ProjectReleaseInfo - have sensible defaults") {
    val proj = dummyProject("test")
    assertEquals(proj.versions, None)
    assertEquals(proj.tagName, None)
    assertEquals(proj.failed, false)
    assertEquals(proj.failureCause, None)
  }

  test("MonorepoTagStrategy - have PerProject and Unified variants") {
    val pp: MonorepoTagStrategy = MonorepoTagStrategy.PerProject
    val u: MonorepoTagStrategy  = MonorepoTagStrategy.Unified
    assertNotEquals(pp, u)
  }

  test("MonorepoContext internal execution state - survive state replacement") {
    withState { state =>
      val ctx = MonorepoContext(state = state).withExecutionState(
        MonorepoExecutionState(
          MonorepoReleasePlan(
            flags = ExecutionFlags(
              useDefaults = true,
              skipTests = false,
              skipPublish = false,
              interactive = false,
              crossBuild = false
            ),
            selectionMode = SelectionMode.AllChanged,
            selectedNames = Seq.empty,
            releaseVersionOverrides = Map.empty,
            nextVersionOverrides = Map.empty,
            globalReleaseVersion = None,
            globalNextVersion = None
          ),
          globalVersionWritten = Some("1.0.0")
        )
      )
      val updated = ctx.withState(state.copy(onFailure = None))

      assertEquals(updated.releasePlan.map(_.selectionMode), Some(SelectionMode.AllChanged))
      assertEquals(updated.globalVersionWritten, Some("1.0.0"))
      assertEquals(updated.useDefaults, true)
    }
  }

  test("MonorepoContext internal execution state - require execution state before recording global version") {
    withState { state =>
      val error = intercept[IllegalStateException] {
        MonorepoContext(state = state).withGlobalVersionWritten("1.0.0")
      }

      assert(clue(error.getMessage).contains("Monorepo execution state not initialized"))
    }
  }

  private def dummyProject(name: String): ProjectReleaseInfo =
    MonorepoTestSupport.dummyProject(name)

  private def withState[A](f: State => A): A = {
    val dir = Files.createTempDirectory("monorepo-ctx-spec").toFile
    try f(TestSupport.dummyState(dir))
    finally TestSupport.deleteRecursively(dir)
  }
}
