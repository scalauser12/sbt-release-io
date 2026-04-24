package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoDummyProjectSupport
import io.release.monorepo.MonorepoSpecSupport
import io.release.monorepo.ProjectReleaseInfo
import io.release.runtime.TrackedContextHandle
import munit.CatsEffectSuite

class MonorepoStepHelpersSpec extends CatsEffectSuite with MonorepoDummyProjectSupport {

  private val contextResource = MonorepoSpecSupport.dummyContextResource("monorepo-step-helpers")

  test("runPerProject skips iterations for projects removed mid-loop") {
    contextResource.use { ctx =>
      dummyProjects("core", "api", "util").flatMap { projects =>
        val pCtx = ctx.withProjects(projects)

        val visited = scala.collection.mutable.ListBuffer.empty[String]

        val action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
          (currentCtx, project) =>
            IO {
              visited += project.name
              if (project.name == "core")
                currentCtx.withProjects(currentCtx.projects.filterNot(_.name == "util"))
              else currentCtx
            }

        MonorepoStepHelpers.runPerProject(pCtx, action).map { result =>
          assertEquals(visited.toList, List("core", "api"))
          assertEquals(result.projects.map(_.name), Seq("core", "api"))
          assert(!result.failed)
        }
      }
    }
  }

  test("runPerProject does not trip updateProject on error paths when the project was removed") {
    contextResource.use { ctx =>
      dummyProjects("core", "util").flatMap { projects =>
        val pCtx = ctx.withProjects(projects)

        val action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
          (currentCtx, project) =>
            if (project.name == "core")
              IO.pure(currentCtx.withProjects(currentCtx.projects.filterNot(_.name == "util")))
            else
              IO.raiseError(new RuntimeException(s"${project.name} must not execute"))

        MonorepoStepHelpers.runPerProject(pCtx, action).map { result =>
          assert(!result.failed)
          assertEquals(result.projects.map(_.name), Seq("core"))
        }
      }
    }
  }

  test("runPerProjectTracked skips iterations for projects removed mid-loop") {
    contextResource.use { ctx =>
      dummyProjects("core", "api", "util").flatMap { projects =>
        val pCtx = ctx.withProjects(projects)

        val visited = scala.collection.mutable.ListBuffer.empty[String]

        val action: (TrackedContextHandle[MonorepoContext], ProjectReleaseInfo) => IO[Unit] =
          (handle, project) =>
            IO { visited += project.name } *>
              handle.update { currentCtx =>
                IO.pure {
                  if (project.name == "core")
                    currentCtx.withProjects(currentCtx.projects.filterNot(_.name == "util"))
                  else currentCtx
                }
              }.void

        TrackedContextHandle.create(pCtx).flatMap { handle =>
          MonorepoStepHelpers.runPerProjectTracked(handle, action) *>
            handle.get.map { result =>
              assertEquals(visited.toList, List("core", "api"))
              assertEquals(result.projects.map(_.name), Seq("core", "api"))
              assert(!result.failed)
            }
        }
      }
    }
  }
}
