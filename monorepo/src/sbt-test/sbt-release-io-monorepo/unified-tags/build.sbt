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
    name := "unified-tags-test",

    // Use unified tag strategy
    releaseIOMonorepoTagStrategy := MonorepoTagStrategy.Unified,

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },

    releaseIgnoreUntrackedFiles := true,

    checkUnifiedTag := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      // Unified strategy: single tag v1.0.0 (NOT core-v1.0.0 / api-v1.0.0)
      assert(
        tags.length == 1,
        s"Expected exactly 1 unified tag but found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(tags.head == "v1.0.0", s"Expected unified tag 'v1.0.0' but got '${tags.head}'")
    },

    checkTagAnnotation := {
      // Verify the unified tag's annotation mentions both projects
      val annotation = "git tag -n1 v1.0.0".!!.trim
      assert(
        annotation.contains("core"),
        s"Expected tag annotation to mention 'core' but got: $annotation"
      )
      assert(
        annotation.contains("api"),
        s"Expected tag annotation to mention 'api' but got: $annotation"
      )
    },

    checkCoreVersion := {
      val contents = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("1.1.0-SNAPSHOT"),
        s"Expected core version 1.1.0-SNAPSHOT but got: $contents"
      )
    },

    checkApiVersion := {
      val contents = IO.read(file("api/version.sbt"))
      assert(
        contents.contains("1.1.0-SNAPSHOT"),
        s"Expected api version 1.1.0-SNAPSHOT but got: $contents"
      )
    }
  )

val checkUnifiedTag    = taskKey[Unit]("Check that a single unified tag was created")
val checkTagAnnotation = taskKey[Unit]("Check unified tag annotation mentions all projects")
val checkCoreVersion   = taskKey[Unit]("Check core version.sbt")
val checkApiVersion    = taskKey[Unit]("Check api version.sbt")
