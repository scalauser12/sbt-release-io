package io.release.monorepo

import io.release.monorepo.internal.*

import cats.effect.IO
import cats.effect.Resource
import io.release.TestSupport
import io.release.runtime.ExecutionFlags
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import munit.CatsEffectSuite

import java.io.File

class MonorepoPreparedSessionSpec extends CatsEffectSuite {

  test("prepare - seed shared startup context and release plan metadata") {
    fixtureResource.use { fixture =>
      val flags = ExecutionFlags(
        useDefaults = true,
        skipTests = true,
        skipPublish = true,
        interactive = true,
        crossBuild = false
      )
      val plan  = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        flags = flags,
        selectedNames = Seq("api")
      )

      MonorepoPreparedSession.prepare(fixture.state, plan).map { session =>
        assertEquals(session.cleanState, fixture.state)
        assertEquals(session.plan, plan)
        assertEquals(session.context.releasePlan, Some(plan))
        assertEquals(session.context.projects.map(_.name), Seq("core", "api"))
        assert(session.context.skipTests)
        assert(session.context.skipPublish)
        assert(session.context.interactive)
      }
    }
  }

  test("prepare - feed the same selected projects to run and check") {
    fixtureResource.use { fixture =>
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        selectedNames = Seq("api")
      )

      for {
        session <- MonorepoPreparedSession.prepare(fixture.state, plan)
        runCtx  <- MonorepoReleaseSteps.detectOrSelectProjects.execute(session.context)
        summary <- MonorepoPreflight.check(
                     session,
                     Seq(MonorepoReleaseSteps.detectOrSelectProjects)
                   )
      } yield {
        assertEquals(runCtx.currentProjects.map(_.name), Seq("api"))
        assertEquals(
          summary.selectionMode,
          MonorepoPreflight.Evaluation.Resolved(SelectionMode.ExplicitSelection)
        )
        assertEquals(summary.projects.map(_.name), Seq("api"))
      }
    }
  }

  private val fixtureResource: Resource[IO, MonorepoSpecSupport.LoadedFixture] =
    MonorepoSpecSupport.loadedFixtureResource("monorepo-prepared-session") { dir =>
      val coreBase = new File(dir, "core")
      val apiBase  = new File(dir, "api")
      coreBase.mkdirs()
      apiBase.mkdirs()

      sbt.IO.write(
        new File(dir, "version.sbt"),
        """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n"
      )
      sbt.IO.write(new File(coreBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
      sbt.IO.write(new File(apiBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")

      TestSupport.initGitRepo(dir)
      TestSupport.commitAll(dir, "Initial commit")

      Seq(
        MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core", "api")),
        MonorepoSpecSupport.versionedProject("core", coreBase),
        MonorepoSpecSupport.versionedProject("api", apiBase)
      )
    }
}
