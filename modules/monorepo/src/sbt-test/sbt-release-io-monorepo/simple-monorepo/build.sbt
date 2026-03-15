import scala.sys.process.*

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
    name := "simple-monorepo-test",

    // Skip push and publish in tests
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },

    // Ignore untracked files in tests
    releaseIOIgnoreUntrackedFiles := true,

    // Consolidated verification task
    checkAll := {
      // Check core version
      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("0.2.0-SNAPSHOT"),
        s"Expected core version 0.2.0-SNAPSHOT in core/version.sbt but got: $coreContents"
      )

      // Check api version
      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("0.2.0-SNAPSHOT"),
        s"Expected api version 0.2.0-SNAPSHOT in api/version.sbt but got: $apiContents"
      )

      // Check git commit count (expected: 3)
      val expected = 3
      val actual   = "git log --oneline".!!.trim.linesIterator.length
      assert(actual == expected, s"Expected $expected commits but found $actual")

      // Check git tags
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.sorted.toList == List("api/v0.1.0", "core/v0.1.0"),
        s"Expected tags [api/v0.1.0, core/v0.1.0] but got [${tags.sorted.mkString(", ")}]"
      )
    }
  )

val checkAll = taskKey[Unit]("Run all verification checks")
