package io.release.monorepo

import io.release.TestSupport
import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseDecisionDefaults
import munit.FunSuite
import sbt.AttributeKey
import sbt.State

import java.nio.file.Files

class MonorepoContextSpec extends FunSuite {

  test("MonorepoContext - update a specific project") {
    withState { state =>
      val projects = Seq(dummyProject("core"), dummyProject("api"))
      val ctx      = MonorepoContext(state = state, projects = projects)
      val updated  =
        ctx.updateProject(projects.head.ref)(_.copy(versions = Some("1.0.0" -> "1.1.0-SNAPSHOT")))

      assertEquals(updated.projects.head.versions, Some("1.0.0" -> "1.1.0-SNAPSHOT"))
      assertEquals(updated.projects(1).versions, None)
    }
  }

  test("MonorepoContext - filter out failed projects in currentProjects") {
    withState { state =>
      val ctx = MonorepoContext(
        state = state,
        projects = Seq(dummyProject("core").copy(failed = true), dummyProject("api"))
      )

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

  test("ProjectReleaseInfo - releaseVersion returns the first element of versions") {
    val proj = dummyProject("core").copy(versions = Some("1.0.0" -> "1.1.0-SNAPSHOT"))

    assertEquals(proj.releaseVersion, Some("1.0.0"))
  }

  test("ProjectReleaseInfo - nextVersion returns the second element of versions") {
    val proj = dummyProject("core").copy(versions = Some("1.0.0" -> "1.1.0-SNAPSHOT"))

    assertEquals(proj.nextVersion, Some("1.1.0-SNAPSHOT"))
  }

  test("ProjectReleaseInfo - releaseVersion and nextVersion return None when versions is None") {
    val proj = dummyProject("core")

    assertEquals(proj.releaseVersion, None)
    assertEquals(proj.nextVersion, None)
  }

  test("MonorepoContext internal execution state - survive state replacement") {
    withState { state =>
      val plan    = MonorepoReleasePlan(
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
        decisionDefaults = ReleaseDecisionDefaults.empty
      )
      val ctx     = MonorepoContext(state = state).withReleasePlan(plan)
      val updated = ctx.withState(state.copy(onFailure = None))

      assertEquals(updated.releasePlan.map(_.selectionMode), Some(SelectionMode.AllChanged))
      assertEquals(updated.useDefaults, true)
    }
  }

  test("MonorepoPreparation.selectionMessage - explicit selection") {
    val projects = Seq(dummyProject("core"), dummyProject("api"))
    val msg      = MonorepoPreparation.selectionMessage(projects, SelectionMode.ExplicitSelection)

    assert(msg.contains("explicitly selected"))
    assert(msg.contains("core, api"))
  }

  test("MonorepoPreparation.selectionMessage - all changed") {
    val projects = Seq(dummyProject("core"))
    val msg      = MonorepoPreparation.selectionMessage(projects, SelectionMode.AllChanged)

    assert(msg.contains("all projects"))
    assert(msg.contains("core"))
  }

  test("MonorepoPreparation.selectionMessage - detect changes") {
    val projects = Seq(dummyProject("core"), dummyProject("api"))
    val msg      = MonorepoPreparation.selectionMessage(projects, SelectionMode.DetectChanges)

    assert(msg.contains("Releasing projects"))
    assert(msg.contains("core, api"))
  }

  private def dummyProject(name: String): ProjectReleaseInfo =
    MonorepoTestSupport.dummyProject(name)

  private def withState[A](f: State => A): A = {
    val dir = Files.createTempDirectory("monorepo-ctx-spec").toFile
    try f(TestSupport.dummyState(dir))
    finally TestSupport.deleteRecursively(dir)
  }
}
