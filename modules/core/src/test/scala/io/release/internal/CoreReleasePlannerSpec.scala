package io.release.internal

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.specs2.mutable.Specification

import java.io.File

class CoreReleasePlannerSpec extends Specification {

  "CoreReleasePlanner.build" should {

    "carry resolved flags, overrides, and tag defaults into the plan" in {
      val versionFile = new File("/tmp/version.sbt")
      val settings    = CoreReleasePlanner.ResolvedSettings(
        versionFile = versionFile,
        readVersion = _ => IO.pure("1.2.3-SNAPSHOT"),
        writeVersion = (_, version) => IO.pure(s"""version := "$version"\n"""),
        useGlobalVersion = true
      )
      val inputs      = CoreReleasePlanner.Inputs(
        useDefaults = true,
        skipTests = true,
        skipPublish = false,
        interactive = true,
        crossBuild = true,
        releaseVersionOverride = Some("1.2.3"),
        nextVersionOverride = Some("1.2.4-SNAPSHOT"),
        tagDefault = Some("k")
      )

      val plan = CoreReleasePlanner.build(settings, inputs)

      (plan.flags must_== ExecutionFlags(
        useDefaults = true,
        skipTests = true,
        skipPublish = false,
        interactive = true,
        crossBuild = true
      )) and
        (plan.version.versionFile must_== versionFile) and
        (plan.version.releaseVersionOverride must beSome("1.2.3")) and
        (plan.version.nextVersionOverride must beSome("1.2.4-SNAPSHOT")) and
        (plan.version.useGlobalVersion must beTrue) and
        (plan.tag.defaultAnswer must beSome("k"))
    }

    "reuse resolved version readers and writers without altering optional overrides" in {
      val versionFile = new File("/tmp/version.sbt")
      val settings    = CoreReleasePlanner.ResolvedSettings(
        versionFile = versionFile,
        readVersion = _ => IO.pure("0.9.0"),
        writeVersion = (_, version) => IO.pure(s"written=$version"),
        useGlobalVersion = false
      )
      val plan        = CoreReleasePlanner.build(
        settings,
        CoreReleasePlanner.Inputs(
          useDefaults = false,
          skipTests = false,
          skipPublish = true,
          interactive = false,
          crossBuild = false,
          releaseVersionOverride = None,
          nextVersionOverride = None,
          tagDefault = None
        )
      )

      (plan.version.readVersion(versionFile).unsafeRunSync() must_== "0.9.0") and
        (plan.version
          .writeVersion(versionFile, "1.0.0")
          .unsafeRunSync() must_== "written=1.0.0") and
        (plan.version.releaseVersionOverride must beNone) and
        (plan.version.nextVersionOverride must beNone) and
        (plan.flags.interactive must beFalse) and
        (plan.flags.useDefaults must beFalse) and
        (plan.flags.skipPublish must beTrue) and
        (plan.tag.defaultAnswer must beNone)
    }
  }
}
