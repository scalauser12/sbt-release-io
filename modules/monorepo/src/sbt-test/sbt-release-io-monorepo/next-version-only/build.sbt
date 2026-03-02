import scala.sys.process._

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "next-version-only-test",

    // Skip push and publish in tests
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },

    releaseIgnoreUntrackedFiles := true,

    checkAll := {
      val coreVer = IO.read(file("core/version.sbt"))
      // next-version override should be 9.9.9-SNAPSHOT
      assert(
        coreVer.contains("9.9.9-SNAPSHOT"),
        s"Expected core version 9.9.9-SNAPSHOT but got: $coreVer"
      )

      val apiVer = IO.read(file("api/version.sbt"))
      // api has no overrides -- next version computed as 0.1.1-SNAPSHOT (bugfix bump of 0.1.0)
      assert(
        apiVer.contains("0.1.1-SNAPSHOT"),
        s"Expected api version 0.1.1-SNAPSHOT but got: $apiVer"
      )

      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      // core was released as 0.1.0 (computed from 0.1.0-SNAPSHOT), next overridden to 9.9.9-SNAPSHOT
      assert(
        tags.contains("core/v0.1.0"),
        s"Expected tag core/v0.1.0 in: ${tags.mkString(", ")}"
      )
    }
  )

val checkAll = taskKey[Unit]("Run all verification checks")
