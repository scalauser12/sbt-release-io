import scala.sys.process._

// 3-level hierarchy: root -> services -> api
// Default releaseIOMonorepoProjects should discover api transitively.

lazy val api = (project in file("services/api"))
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

lazy val services = (project in file("services"))
  .aggregate(api)
  .settings(
    name         := "services",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(services)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name         := "transitive-agg-test",
    scalaVersion := "2.12.18",

    // Do NOT override releaseIOMonorepoProjects — use the default
    // which should transitively discover: services, api

    releaseIOMonorepoDetectChanges := false,

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" ||
      step.name == "publish-artifacts" ||
      step.name == "run-clean" ||
      step.name == "run-tests"
    },

    releaseIgnoreUntrackedFiles := true,

    checkAll := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      // Both services and api should be released (transitively discovered)
      assert(
        tags.length == 2,
        s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(
        tags.toList == List("api/v1.0.0", "services/v1.0.0"),
        s"Expected [api/v1.0.0, services/v1.0.0] but got [${tags.mkString(", ")}]"
      )

      val servicesContents = IO.read(file("services/version.sbt"))
      assert(
        servicesContents.contains("1.1.0-SNAPSHOT"),
        s"Expected services version 1.1.0-SNAPSHOT but got: $servicesContents"
      )

      val apiContents = IO.read(file("services/api/version.sbt"))
      assert(
        apiContents.contains("1.1.0-SNAPSHOT"),
        s"Expected api version 1.1.0-SNAPSHOT but got: $apiContents"
      )
    }
  )

val checkAll = taskKey[Unit]("Run all verification checks")
