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
    name := "simple-monorepo-test",

    // Skip push and publish in tests
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },

    // Ignore untracked files in tests
    releaseIgnoreUntrackedFiles := true,

    // Custom verification tasks
    checkGitCommitCount := {
      import sbt.complete.DefaultParsers._
      val expected = spaceDelimited("<count>").parsed.head.toInt
      val actual   = "git log --oneline".!!.trim.linesIterator.length
      assert(actual == expected, s"Expected $expected commits but found $actual")
    },

    checkGitTags := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      // Expected: per-project tags (core-v0.1.0, api-v0.1.0)
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.sorted.toList == List("api-v0.1.0", "core-v0.1.0"),
        s"Expected tags [api-v0.1.0, core-v0.1.0] but got [${tags.sorted.mkString(", ")}]"
      )
    },

    checkCoreVersion := {
      val contents = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("0.2.0-SNAPSHOT"),
        s"Expected core version 0.2.0-SNAPSHOT in core/version.sbt but got: $contents"
      )
    },

    checkApiVersion := {
      val contents = IO.read(file("api/version.sbt"))
      assert(
        contents.contains("0.2.0-SNAPSHOT"),
        s"Expected api version 0.2.0-SNAPSHOT in api/version.sbt but got: $contents"
      )
    }
  )

val checkGitCommitCount = inputKey[Unit]("Assert git has the expected number of commits")
val checkGitTags        = taskKey[Unit]("Check git tags")
val checkCoreVersion    = taskKey[Unit]("Check core version.sbt")
val checkApiVersion     = taskKey[Unit]("Check api version.sbt")
