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

val checkFailure = taskKey[Unit]("Verify tag collision with interactive + with-defaults")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "interactive-with-defaults-tag-test",

    releaseIOMonorepoInteractive      := true,
    releaseIOMonorepoUseGlobalVersion := true,

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkFailure := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      // Pre-existing tag should be preserved, no additional tags
      assert(
        tags.contains("core/v1.0.0"),
        s"Expected pre-existing tag core/v1.0.0 but tags are: ${tags.mkString(", ")}"
      )

      // Version files should contain release version (not next SNAPSHOT)
      // because tag failure propagated globally and skipped set-next-version
      val contents = IO.read(file("version.sbt"))
      assert(
        !contents.contains("1.1.0-SNAPSHOT"),
        s"version.sbt should not contain next SNAPSHOT after tag failure: $contents"
      )
    }
  )
