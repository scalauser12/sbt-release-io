package io.release.steps

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.release.ReleaseContext
import io.release.TestSupport
import io.release.internal.{CoreExecutionState, CoreReleasePlan, ExecutionFlags}
import munit.FunSuite

import java.io.File
import java.nio.file.Files

class VersionStepsSpec extends FunSuite {

  test("resolveVersionPlan - use live version settings even when a startup plan is attached") {
    withTempDir { dir =>
      val resolvedFile = new File(dir, "resolved-version.sbt")
      val ctx          = ReleaseContext(state = TestSupport.dummyState(dir)).withExecutionState(
        CoreExecutionState(
          CoreReleasePlan(
            flags = ExecutionFlags(
              useDefaults = false,
              skipTests = false,
              skipPublish = false,
              interactive = false,
              crossBuild = false
            ),
            releaseVersionOverride = Some("1.2.3"),
            nextVersionOverride = Some("1.2.4-SNAPSHOT"),
            tagDefault = None
          )
        )
      )

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

      assertEquals(result.versionFile, resolvedFile)
      assertEquals(result.releaseVersionOverride, Some("1.2.3"))
      assertEquals(result.nextVersionOverride, Some("1.2.4-SNAPSHOT"))
      assert(result.useGlobalVersion)
      assertEquals(result.readVersion(resolvedFile).unsafeRunSync(), "1.2.3-SNAPSHOT")
      assertEquals(
        result.versionFileContents(resolvedFile, "1.2.3").unsafeRunSync(),
        "resolved=1.2.3"
      )
    }
  }

  test(
    "resolveVersionPlan - delegate live resolution to CoreVersionResolver and read overrides"
  ) {
    withTempDir { dir =>
      val fallbackFile = new File(dir, "fallback-version.sbt")
      var resolverRuns = 0
      val ctx          = ReleaseContext(state = TestSupport.dummyState(dir)).withExecutionState(
        CoreExecutionState(
          CoreReleasePlan(
            flags = ExecutionFlags(
              useDefaults = false,
              skipTests = false,
              skipPublish = false,
              interactive = false,
              crossBuild = false
            ),
            releaseVersionOverride = Some("2.0.0"),
            nextVersionOverride = Some("2.0.1-SNAPSHOT"),
            tagDefault = None
          )
        )
      )

      val result = VersionSteps.resolveVersionPlan(
        ctx,
        _ => {
          resolverRuns += 1
          VersionSteps.ResolvedSettings(
            versionFile = fallbackFile,
            readVersion = _ => IO.pure("1.9.9-SNAPSHOT"),
            versionFileContents = (_, version) => IO.pure(s"fallback=$version"),
            useGlobalVersion = false
          )
        }
      )

      assertEquals(resolverRuns, 1)
      assertEquals(result.versionFile, fallbackFile)
      assertEquals(result.releaseVersionOverride, Some("2.0.0"))
      assertEquals(result.nextVersionOverride, Some("2.0.1-SNAPSHOT"))
      assert(!result.useGlobalVersion)
      assertEquals(result.readVersion(fallbackFile).unsafeRunSync(), "1.9.9-SNAPSHOT")
      assertEquals(
        result.versionFileContents(fallbackFile, "2.0.0").unsafeRunSync(),
        "fallback=2.0.0"
      )
    }
  }

  test("defaultReadVersion - parse a standard version line") {
    withTempDir { dir =>
      val f = writeVersionFile(dir, """ThisBuild / version := "1.2.3-SNAPSHOT"""")
      assertEquals(VersionSteps.defaultReadVersion(f).unsafeRunSync(), "1.2.3-SNAPSHOT")
    }
  }

  test("defaultReadVersion - skip single-line // comments") {
    withTempDir { dir =>
      val f = writeVersionFile(
        dir,
        """// version := "9.9.9"
          |version := "0.1.0"
          |""".stripMargin
      )
      assertEquals(VersionSteps.defaultReadVersion(f).unsafeRunSync(), "0.1.0")
    }
  }

  test("defaultReadVersion - skip versions inside multiline block comments") {
    withTempDir { dir =>
      val f = writeVersionFile(
        dir,
        """/*
          |ThisBuild / version := "9.9.9"
          |*/
          |ThisBuild / version := "0.1.0-SNAPSHOT"
          |""".stripMargin
      )
      assertEquals(
        VersionSteps.defaultReadVersion(f).unsafeRunSync(),
        "0.1.0-SNAPSHOT"
      )
    }
  }

  test("defaultReadVersion - skip single-line block comments") {
    withTempDir { dir =>
      val f = writeVersionFile(
        dir,
        """/* version := "9.9.9" */
          |version := "0.1.0"
          |""".stripMargin
      )
      assertEquals(VersionSteps.defaultReadVersion(f).unsafeRunSync(), "0.1.0")
    }
  }

  test("defaultReadVersion - skip block comments with *-prefixed lines") {
    withTempDir { dir =>
      val f = writeVersionFile(
        dir,
        """/*
          | * version := "9.9.9"
          | */
          |version := "0.1.0"
          |""".stripMargin
      )
      assertEquals(VersionSteps.defaultReadVersion(f).unsafeRunSync(), "0.1.0")
    }
  }

  test("defaultReadVersion - raise IllegalStateException when no version can be parsed") {
    withTempDir { dir =>
      val f = writeVersionFile(
        dir,
        """// version := "9.9.9"
          |/*
          |ThisBuild / version := "0.1.0"
          |*/
          |lazy val root = project
          |""".stripMargin
      )

      val err = intercept[IllegalStateException] {
        VersionSteps.defaultReadVersion(f).unsafeRunSync()
      }

      assert(err.getMessage.contains("Could not parse version"))
      assert(err.getMessage.contains(f.getName))
    }
  }

  private def writeVersionFile(dir: File, content: String): File = {
    val f = new File(dir, "version.sbt")
    sbt.IO.write(f, content)
    f
  }

  private def withTempDir[A](f: File => A): A = {
    val dir = Files.createTempDirectory("version-steps-spec").toFile
    try f(dir)
    finally TestSupport.deleteRecursively(dir)
  }
}
