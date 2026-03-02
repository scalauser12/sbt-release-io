import scala.sys.process._

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
    // NOTE: publishTo is intentionally NOT set
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
    // NOTE: publishTo is intentionally NOT set
  )

val checkAll = taskKey[Unit]("Run all verification checks")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                         := "skip-publish-test",
    // Skip publish — bypasses publishTo validation in check phase
    releaseIOMonorepoSkipPublish := true,
    // Keep publish-artifacts in process (only filter push-changes)
    releaseIOMonorepoProcess     := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes"
    },
    releaseIgnoreUntrackedFiles  := true,
    checkAll                     := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("api/v0.1.0", "core/v0.1.0"),
        s"Expected tags [api/v0.1.0, core/v0.1.0] but got [${tags.mkString(", ")}]"
      )

      val coreVer = IO.read(file("core/version.sbt"))
      assert(
        coreVer.contains("0.2.0-SNAPSHOT"),
        s"Expected core version 0.2.0-SNAPSHOT but got: $coreVer"
      )

      val apiVer = IO.read(file("api/version.sbt"))
      assert(
        apiVer.contains("0.2.0-SNAPSHOT"),
        s"Expected api version 0.2.0-SNAPSHOT but got: $apiVer"
      )
    }
  )
