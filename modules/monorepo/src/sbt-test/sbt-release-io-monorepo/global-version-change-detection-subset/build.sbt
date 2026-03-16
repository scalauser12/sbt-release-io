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
    name := "global-version-change-detection-subset-test",

    releaseIOMonorepoUseGlobalVersion := true,
    releaseIOMonorepoDetectChanges    := true,

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" ||
      step.name == "publish-artifacts" ||
      step.name == "run-clean" ||
      step.name == "run-tests"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkFailureArtifacts := {
      val tags        = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(
        tags.length == 2,
        s"Expected only the 2 pre-existing tags after failure, found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(
        tags.toList == List("api/v0.1.0", "core/v0.1.0"),
        s"Expected tags [api/v0.1.0, core/v0.1.0] but got [${tags.mkString(", ")}]"
      )
      val contents    = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("0.2.0-SNAPSHOT"),
        s"Expected core version to remain 0.2.0-SNAPSHOT but got: $contents"
      )
      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("0.2.0-SNAPSHOT"),
        s"Expected api version to remain 0.2.0-SNAPSHOT but got: $apiContents"
      )
      assert(
        !file("version.sbt").exists(),
        "Global version file should not be created on early failure"
      )

      val commitCount = "git rev-list --count HEAD".!!.trim.toInt
      assert(
        commitCount == 2,
        s"Expected no release commits after failure, found commit count: $commitCount"
      )
    }
  )

val checkFailureArtifacts =
  taskKey[Unit](
    "Validate no tags/versions/commits were changed after global-version subset failure"
  )
