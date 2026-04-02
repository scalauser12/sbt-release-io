package io.release.monorepo.steps

import cats.effect.IO
import io.release.TestSupport
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoSpecSupport
import munit.CatsEffectSuite
import sbt.Keys.*
import sbt.LocalProject
import sbt.Project
import sbt.State
import sbt.internal.util.AttributeMap
import sbt.internal.util.ConsoleOut
import sbt.internal.util.GlobalLogging
import sbt.internal.util.MainAppender

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class MonorepoCrossBuildSpec extends CatsEffectSuite {

  test("multi-version cross-build logs include the project name") {
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
            (currentCtx: MonorepoContext, _: io.release.monorepo.ProjectReleaseInfo) =>
              IO.pure(currentCtx)

          MonorepoCrossBuild
            .runPerProjectWithCrossBuild(
              ctx,
              action,
              crossBuild = true,
              enableCrossBuild = true
            )
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
              }
            }
        }
      }
  }

  private def bufferedState(dir: File, consoleBuffer: ByteArrayOutputStream): State = {
    val logFile       = new File(dir, "sbt-test.log")
    val consoleOut    = ConsoleOut.printStreamOut(new PrintStream(consoleBuffer))
    val globalLogging =
      GlobalLogging.initial(
        MainAppender.globalDefault(consoleOut),
        logFile,
        consoleOut
      )

    State(
      configuration = TestSupport.dummyAppConfiguration(dir),
      definedCommands = Nil,
      exitHooks = Set.empty,
      onFailure = None,
      remainingCommands = Nil,
      history = State.newHistory,
      attributes = AttributeMap.empty,
      globalLogging = globalLogging,
      currentCommand = None,
      next = State.Continue
    )
  }
}
