package io.release.monorepo

import io.release.monorepo.internal.*

import cats.effect.IO
import io.release.TestSupport
import io.release.runtime.ExecutionFlags
import io.release.runtime.ReleaseDecisionDefaults
import munit.CatsEffectSuite
import sbt.AttributeKey

class MonorepoContextSpec extends CatsEffectSuite with MonorepoDummyProjectSupport {

  private val stateResource = TestSupport.dummyStateResource("monorepo-ctx-spec")

  test("MonorepoContext - update a specific project") {
    stateResource.use { state =>
      dummyProjects("core", "api").map { projects =>
        val ctx     = MonorepoContext(state = state, projects = projects)
        val updated =
          ctx.updateProject(projects.head.ref)(_.copy(versions = Some("1.0.0" -> "1.1.0-SNAPSHOT")))

        assertEquals(updated.projects.head.versions, Some("1.0.0" -> "1.1.0-SNAPSHOT"))
        assertEquals(updated.projects(1).versions, None)
      }
    }
  }

  test("MonorepoContext - filter out failed projects in currentProjects") {
    stateResource.use { state =>
      dummyProjects("core", "api").map { projects =>
        val ctx = MonorepoContext(
          state = state,
          projects = Seq(projects.head.copy(failed = true), projects(1))
        )

        assertEquals(ctx.currentProjects.map(_.name), Seq("api"))
      }
    }
  }

  test("MonorepoContext - manage typed metadata") {
    stateResource.use { state =>
      IO {
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
  }

  test("MonorepoContext - mark as failed") {
    stateResource.use { state =>
      IO {
        val ctx = MonorepoContext(state = state)

        assertEquals(ctx.failed, false)
        assertEquals(ctx.fail.failed, true)
      }
    }
  }

  test("MonorepoContext - replace projects via withProjects") {
    stateResource.use { state =>
      dummyProjects("old", "new1", "new2").map { projects =>
        val ctx     = MonorepoContext(state = state, projects = Seq(projects.head))
        val updated = ctx.withProjects(projects.tail)

        assertEquals(updated.projects.map(_.name), Seq("new1", "new2"))
      }
    }
  }

  test("ProjectReleaseInfo - have sensible defaults") {
    dummyProject("test").map { proj =>
      assertEquals(proj.versions, None)
      assertEquals(proj.tagName, None)
      assertEquals(proj.failed, false)
      assertEquals(proj.failureCause, None)
    }
  }

  test("ProjectReleaseInfo - releaseVersion returns the first element of versions") {
    dummyProject("core").map { proj =>
      val versioned = proj.copy(versions = Some("1.0.0" -> "1.1.0-SNAPSHOT"))

      assertEquals(versioned.releaseVersion, Some("1.0.0"))
    }
  }

  test("ProjectReleaseInfo - nextVersion returns the second element of versions") {
    dummyProject("core").map { proj =>
      val versioned = proj.copy(versions = Some("1.0.0" -> "1.1.0-SNAPSHOT"))

      assertEquals(versioned.nextVersion, Some("1.1.0-SNAPSHOT"))
    }
  }

  test("ProjectReleaseInfo - releaseVersion and nextVersion return None when versions is None") {
    dummyProject("core").map { proj =>
      assertEquals(proj.releaseVersion, None)
      assertEquals(proj.nextVersion, None)
    }
  }

  test("MonorepoContext internal execution state - survive state replacement") {
    stateResource.use { state =>
      IO {
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
  }

  test("MonorepoPreparation.selectionMessage - explicit selection") {
    dummyProjects("core", "api").map { projects =>
      val msg = MonorepoPreparation.selectionMessage(projects, SelectionMode.ExplicitSelection)

      assert(msg.contains("explicitly selected"))
      assert(msg.contains("core, api"))
    }
  }

  test("MonorepoPreparation.selectionMessage - all changed") {
    dummyProject("core").map { project =>
      val msg = MonorepoPreparation.selectionMessage(Seq(project), SelectionMode.AllChanged)

      assert(msg.contains("all projects"))
      assert(msg.contains("core"))
    }
  }

  test("MonorepoPreparation.selectionMessage - detect changes") {
    dummyProjects("core", "api").map { projects =>
      val msg = MonorepoPreparation.selectionMessage(projects, SelectionMode.DetectChanges)

      assert(msg.contains("Releasing projects"))
      assert(msg.contains("core, api"))
    }
  }
}
