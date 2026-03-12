package io.release.steps

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.release.internal.{CoreReleasePlan, ExecutionFlags}
import io.release.TestSupport
import org.specs2.mutable.Specification

import java.io.File
import java.nio.file.Files

class VersionStepsSpec extends Specification {

  "VersionSteps.resolveVersionPlan" should {

    "use live version settings even when a startup plan is attached" in withTempDir { dir =>
      val resolvedFile = new File(dir, "resolved-version.sbt")
      val state        =
        CoreReleasePlan.attach(
          TestSupport.dummyState(dir),
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

      val result = VersionSteps.resolveVersionPlan(
        state,
        _ =>
          VersionSteps.ResolvedSettings(
            versionFile = resolvedFile,
            readVersion = _ => IO.pure("1.2.3-SNAPSHOT"),
            writeVersion = (_, version) => IO.pure(s"resolved=$version"),
            useGlobalVersion = true
          )
      )

      (result.versionFile must_== resolvedFile) and
        (result.releaseVersionOverride must beSome("1.2.3")) and
        (result.nextVersionOverride must beSome("1.2.4-SNAPSHOT")) and
        (result.useGlobalVersion must beTrue) and
        (result.readVersion(resolvedFile).unsafeRunSync() must_== "1.2.3-SNAPSHOT") and
        (result.writeVersion(resolvedFile, "1.2.3").unsafeRunSync() must_== "resolved=1.2.3")
    }

    "delegate live resolution to CoreVersionResolver and read overrides from plan" in
      withTempDir { dir =>
        val fallbackFile = new File(dir, "fallback-version.sbt")
        var resolverRuns = 0
        val state        =
          CoreReleasePlan.attach(
            TestSupport.dummyState(dir),
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

        val result = VersionSteps.resolveVersionPlan(
          state,
          _ => {
            resolverRuns += 1
            VersionSteps.ResolvedSettings(
              versionFile = fallbackFile,
              readVersion = _ => IO.pure("1.9.9-SNAPSHOT"),
              writeVersion = (_, version) => IO.pure(s"fallback=$version"),
              useGlobalVersion = false
            )
          }
        )

        (resolverRuns must_== 1) and
          (result.versionFile must_== fallbackFile) and
          (result.releaseVersionOverride must beSome("2.0.0")) and
          (result.nextVersionOverride must beSome("2.0.1-SNAPSHOT")) and
          (result.useGlobalVersion must beFalse) and
          (result.readVersion(fallbackFile).unsafeRunSync() must_== "1.9.9-SNAPSHOT") and
          (result.writeVersion(fallbackFile, "2.0.0").unsafeRunSync() must_== "fallback=2.0.0")
      }
  }

  private def withTempDir[A](f: File => A): A = {
    val dir = Files.createTempDirectory("version-steps-spec").toFile
    try f(dir)
    finally TestSupport.deleteRecursively(dir)
  }
}
