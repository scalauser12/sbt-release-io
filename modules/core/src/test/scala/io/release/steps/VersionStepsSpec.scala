package io.release.steps

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.release.internal.{CoreReleasePlan, CoreReleasePlanner, ExecutionFlags, TagPlan, VersionPlan}
import io.release.{ReleaseKeys, TestSupport}
import org.specs2.mutable.Specification

import java.io.File
import java.nio.file.Files

class VersionStepsSpec extends Specification {

  "VersionSteps.resolveVersionPlan" should {

    "prefer the attached core release plan over fallback resolution" in withTempDir {
      dir =>
        val attachedFile = new File(dir, "attached-version.sbt")
        val state        =
          CoreReleasePlanner.attach(
            TestSupport
              .dummyState(dir)
              .put(ReleaseKeys.commandLineReleaseVersion, Some("9.9.9"))
              .put(ReleaseKeys.commandLineNextVersion, Some("10.0.0-SNAPSHOT")),
            CoreReleasePlan(
              flags = ExecutionFlags(
                useDefaults = false,
                skipTests = false,
                skipPublish = false,
                interactive = false,
                crossBuild = false
              ),
              version = VersionPlan(
                versionFile = attachedFile,
                readVersion = _ => IO.pure("1.2.3-SNAPSHOT"),
                writeVersion = (_, version) => IO.pure(s"attached=$version"),
                releaseVersionOverride = Some("1.2.3"),
                nextVersionOverride = Some("1.2.4-SNAPSHOT"),
                useGlobalVersion = true
              ),
              tag = TagPlan(defaultAnswer = None)
            )
          )

        val result = VersionSteps.resolveVersionPlan(
          state,
          _ => throw new IllegalStateException("fallback resolver should not run")
        )

        (result.versionFile must_== attachedFile) and
          (result.releaseVersionOverride must beSome("1.2.3")) and
          (result.nextVersionOverride must beSome("1.2.4-SNAPSHOT")) and
          (result.useGlobalVersion must beTrue) and
          (result.readVersion(attachedFile).unsafeRunSync() must_== "1.2.3-SNAPSHOT") and
          (result.writeVersion(attachedFile, "1.2.3").unsafeRunSync() must_== "attached=1.2.3")
    }

    "delegate fallback resolution to CoreReleasePlanner.resolve and preserve CLI overrides" in
      withTempDir { dir =>
        val fallbackFile = new File(dir, "fallback-version.sbt")
        var resolverRuns = 0
        val state        =
          TestSupport
            .dummyState(dir)
            .put(ReleaseKeys.commandLineReleaseVersion, Some("2.0.0"))
            .put(ReleaseKeys.commandLineNextVersion, Some("2.0.1-SNAPSHOT"))

        val result = VersionSteps.resolveVersionPlan(
          state,
          _ => {
            resolverRuns += 1
            CoreReleasePlanner.ResolvedSettings(
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
