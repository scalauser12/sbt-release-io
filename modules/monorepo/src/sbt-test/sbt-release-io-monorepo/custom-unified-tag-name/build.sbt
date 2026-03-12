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

val checkAll = taskKey[Unit]("Run all verification checks")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "custom-unified-tag-name-test",

    // Use unified tag strategy with custom format
    releaseIOMonorepoTagStrategy    := MonorepoTagStrategy.Unified,
    releaseIOMonorepoUnifiedTagName := ((ver: String) => s"release-v$ver"),

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkAll := {
      // Check exactly one tag exists with the custom format
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(
        tags.length == 1,
        s"Expected exactly 1 unified tag but found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(
        tags.head == "release-v1.0.0",
        s"Expected unified tag 'release-v1.0.0' but got '${tags.head}'"
      )

      // Verify the default format was NOT used
      val defaultTag = "git tag -l v1.0.0".!!.trim
      assert(
        defaultTag.isEmpty,
        s"Default tag 'v1.0.0' should NOT exist but was found"
      )

      // Check tag annotation mentions both projects
      val annotation = "git tag -n1 release-v1.0.0".!!.trim
      assert(
        annotation.contains("core"),
        s"Expected tag annotation to mention 'core' but got: $annotation"
      )
      assert(
        annotation.contains("api"),
        s"Expected tag annotation to mention 'api' but got: $annotation"
      )

      // Check next versions
      val coreVer = IO.read(file("core/version.sbt"))
      assert(
        coreVer.contains("1.1.0-SNAPSHOT"),
        s"Expected core version 1.1.0-SNAPSHOT but got: $coreVer"
      )

      val apiVer = IO.read(file("api/version.sbt"))
      assert(
        apiVer.contains("1.1.0-SNAPSHOT"),
        s"Expected api version 1.1.0-SNAPSHOT but got: $apiVer"
      )
    }
  )
