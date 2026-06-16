package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.TestSupport
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoSpecSupport
import io.release.monorepo.internal.steps.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.TrackedContextHandle
import munit.CatsEffectSuite
import sbt.Keys.*
import sbt.LocalProject
import sbt.Project

import java.io.File

class MonorepoCrossBuildSpec extends CatsEffectSuite {

  test("multi-version cross-build logs include the project name and monorepo prefix") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-cross-build-log") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"))
            .settings(scalaVersion := TestSupport.CurrentScalaVersion),
          Project("core", coreBase).settings(
            scalaVersion           := TestSupport.CurrentScalaVersion,
            crossScalaVersions     := Seq(
              TestSupport.CurrentScalaVersion,
              TestSupport.alternateScalaVersion
            )
          )
        )
      }
      .use { fixture =>
        IO.blocking {
          val buffered    = TestSupport.bufferedState(fixture.dir)
          val loggedState = fixture.state.copy(globalLogging = buffered.state.globalLogging)
          val ctx         = MonorepoContext(
            state = loggedState,
            projects = Seq(fixture.projectInfo("core"))
          )
          (ctx, buffered.consoleBuffer)
        }.flatMap { case (ctx, consoleBuffer) =>
          val action =
            (_: TrackedContextHandle[MonorepoContext], _: io.release.monorepo.ProjectReleaseInfo) =>
              IO.unit

          TrackedContextHandle
            .create(ctx)
            .flatMap { handle =>
              MonorepoCrossBuild.runPerProjectWithCrossBuildTracked(
                handle,
                action,
                crossBuild = true,
                enableCrossBuild = true
              )
            }
            .flatMap { _ =>
              IO.blocking {
                val log = consoleBuffer.toString("UTF-8")
                assert(
                  log.contains(
                    s"Cross-building core with Scala ${TestSupport.CurrentScalaVersion}"
                  )
                )
                assert(
                  log.contains(
                    s"Cross-building core with Scala ${TestSupport.alternateScalaVersion}"
                  )
                )
                assert(
                  log.contains(
                    s"${ReleaseLogPrefixes.Monorepo} Setting scala version to ${TestSupport.CurrentScalaVersion}"
                  )
                )
                assert(
                  log.contains(
                    s"${ReleaseLogPrefixes.Monorepo} Setting scala version to ${TestSupport.alternateScalaVersion}"
                  )
                )
              }
            }
        }
      }
  }
}
