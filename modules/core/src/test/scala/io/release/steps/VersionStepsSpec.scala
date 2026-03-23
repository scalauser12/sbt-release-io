package io.release.steps

import cats.effect.IO
import io.release.{ReleaseContext, TestAssertions, TestSupport}

import java.util.concurrent.atomic.AtomicInteger
import io.release.internal.{CoreExecutionState, CoreReleasePlan, ExecutionFlags}
import munit.CatsEffectSuite

import java.io.File

class VersionStepsSpec extends CatsEffectSuite {
  private val fixturePrefix = "version-steps-spec"

  private val startupFlags = ExecutionFlags(
    useDefaults = false,
    skipTests = false,
    skipPublish = false,
    interactive = false,
    crossBuild = false
  )

  test("resolveVersionPlan - use live version settings even when a startup plan is attached") {
    TestSupport.dummyContextResource(fixturePrefix).use { baseCtx =>
      val dir          = baseCtx.state.configuration.baseDirectory
      val resolvedFile = new File(dir, "resolved-version.sbt")
      val ctx          = withStartupPlan(baseCtx, "1.2.3", "1.2.4-SNAPSHOT")

      val result = VersionSteps.resolveVersionPlan(
        ctx,
        _ =>
          VersionSteps.ResolvedSettings(
            versionFile = resolvedFile,
            readVersion = _ => IO.pure("1.2.3-SNAPSHOT"),
            versionFileContents = (_, version) => IO.pure(s"resolved=$version"),
            useGlobalVersion = true
          )
      )

      for {
        readContents <- result.readVersion(resolvedFile)
        fileContents <- result.versionFileContents(resolvedFile, "1.2.3")
      } yield {
        assertEquals(result.versionFile, resolvedFile)
        assertEquals(result.releaseVersionOverride, Some("1.2.3"))
        assertEquals(result.nextVersionOverride, Some("1.2.4-SNAPSHOT"))
        assert(result.useGlobalVersion)
        assertEquals(readContents, "1.2.3-SNAPSHOT")
        assertEquals(fileContents, "resolved=1.2.3")
      }
    }
  }

  test("resolveVersionPlan - leave overrides empty when no execution state is attached") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val dir      = ctx.state.configuration.baseDirectory
      val liveFile = new File(dir, "live-version.sbt")

      val result = VersionSteps.resolveVersionPlan(
        ctx,
        _ =>
          VersionSteps.ResolvedSettings(
            versionFile = liveFile,
            readVersion = _ => IO.pure("0.9.0-SNAPSHOT"),
            versionFileContents = (_, version) => IO.pure(s"live=$version"),
            useGlobalVersion = false
          )
      )

      for {
        readContents <- result.readVersion(liveFile)
        fileContents <- result.versionFileContents(liveFile, "1.0.0")
      } yield {
        assertEquals(result.versionFile, liveFile)
        assertEquals(result.releaseVersionOverride, None)
        assertEquals(result.nextVersionOverride, None)
        assert(!result.useGlobalVersion)
        assertEquals(readContents, "0.9.0-SNAPSHOT")
        assertEquals(fileContents, "live=1.0.0")
      }
    }
  }

  test(
    "resolveVersionPlan - delegate live resolution to CoreVersionResolver and read overrides"
  ) {
    TestSupport.dummyContextResource(fixturePrefix).use { baseCtx =>
      val dir          = baseCtx.state.configuration.baseDirectory
      val fallbackFile = new File(dir, "fallback-version.sbt")
      val resolverRuns = new AtomicInteger(0)
      val ctx          = withStartupPlan(baseCtx, "2.0.0", "2.0.1-SNAPSHOT")

      val result = VersionSteps.resolveVersionPlan(
        ctx,
        _ => {
          resolverRuns.incrementAndGet()
          VersionSteps.ResolvedSettings(
            versionFile = fallbackFile,
            readVersion = _ => IO.pure("1.9.9-SNAPSHOT"),
            versionFileContents = (_, version) => IO.pure(s"fallback=$version"),
            useGlobalVersion = false
          )
        }
      )

      for {
        readContents <- result.readVersion(fallbackFile)
        fileContents <- result.versionFileContents(fallbackFile, "2.0.0")
      } yield {
        assertEquals(resolverRuns.get(), 1)
        assertEquals(result.versionFile, fallbackFile)
        assertEquals(result.releaseVersionOverride, Some("2.0.0"))
        assertEquals(result.nextVersionOverride, Some("2.0.1-SNAPSHOT"))
        assert(!result.useGlobalVersion)
        assertEquals(readContents, "1.9.9-SNAPSHOT")
        assertEquals(fileContents, "fallback=2.0.0")
      }
    }
  }

  private val defaultReadVersionCases = Seq(
    (
      "defaultReadVersion - parse a standard version line",
      """ThisBuild / version := "1.2.3-SNAPSHOT"""",
      "1.2.3-SNAPSHOT"
    ),
    (
      "defaultReadVersion - skip single-line // comments",
      """// version := "9.9.9"
        |version := "0.1.0"
        |""".stripMargin,
      "0.1.0"
    ),
    (
      "defaultReadVersion - skip versions inside multiline block comments",
      """/*
        |ThisBuild / version := "9.9.9"
        |*/
        |ThisBuild / version := "0.1.0-SNAPSHOT"
        |""".stripMargin,
      "0.1.0-SNAPSHOT"
    ),
    (
      "defaultReadVersion - skip single-line block comments",
      """/* version := "9.9.9" */
        |version := "0.1.0"
        |""".stripMargin,
      "0.1.0"
    ),
    (
      "defaultReadVersion - skip block comments with *-prefixed lines",
      """/*
        | * version := "9.9.9"
        | */
        |version := "0.1.0"
        |""".stripMargin,
      "0.1.0"
    )
  )

  defaultReadVersionCases.foreach { case (name, contents, expected) =>
    test(name) {
      TestSupport
        .tempDirResource(fixturePrefix)
        .use(dir => assertReadVersion(dir, contents, expected))
    }
  }

  test("defaultReadVersion - raise IllegalStateException when no version can be parsed") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      writeVersionFile(
        dir,
        """// version := "9.9.9"
          |/*
          |ThisBuild / version := "0.1.0"
          |*/
          |lazy val root = project
          |""".stripMargin
      ).flatMap { file =>
        TestAssertions.assertFailure[IllegalStateException, String](
          VersionSteps.defaultReadVersion(file)
        ) { err =>
          assert(err.getMessage.contains("Could not parse version"))
          assert(err.getMessage.contains(file.getName))
        }
      }
    }
  }

  private def assertReadVersion(dir: File, content: String, expected: String): IO[Unit] =
    writeVersionFile(dir, content).flatMap { file =>
      VersionSteps.defaultReadVersion(file).map(result => assertEquals(result, expected))
    }

  private def withStartupPlan(
      ctx: ReleaseContext,
      releaseVersion: String,
      nextVersion: String
  ): ReleaseContext =
    ctx.withExecutionState(
      CoreExecutionState(
        CoreReleasePlan(
          flags = startupFlags,
          releaseVersionOverride = Some(releaseVersion),
          nextVersionOverride = Some(nextVersion),
          tagDefault = None
        )
      )
    )

  private def writeVersionFile(dir: File, content: String): IO[File] = {
    val file = new File(dir, "version.sbt")
    IO.blocking {
      sbt.IO.write(file, content)
      file
    }
  }
}
