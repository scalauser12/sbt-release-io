import scala.sys.process._

// 3-level hierarchy: root -> services -> api
// When only services/api/ changes, services should NOT be detected as changed.

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
    name         := "nested-parent-test",
    scalaVersion := "2.12.18",

    releaseIOMonorepoDetectChanges := true,

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" ||
      step.name == "publish-artifacts" ||
      step.name == "run-clean" ||
      step.name == "run-tests"
    },

    releaseIgnoreUntrackedFiles := true,

    checkAll := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      // After release: original 2 tags + api/v0.2.0 (services unchanged)
      assert(
        tags.length == 3,
        s"Expected 3 tags but found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(
        tags.toList == List("api/v0.1.0", "api/v0.2.0", "services/v0.1.0"),
        s"Expected [api/v0.1.0, api/v0.2.0, services/v0.1.0] but got [${tags.mkString(", ")}]"
      )

      // services was NOT released, so its version should be unchanged
      val servicesContents = IO.read(file("services/version.sbt"))
      assert(
        servicesContents.contains("0.2.0-SNAPSHOT"),
        s"Expected services version 0.2.0-SNAPSHOT (unchanged) but got: $servicesContents"
      )

      val apiContents = IO.read(file("services/api/version.sbt"))
      assert(
        apiContents.contains("0.3.0-SNAPSHOT"),
        s"Expected api version 0.3.0-SNAPSHOT but got: $apiContents"
      )
    }
  )

val checkAll = taskKey[Unit]("Run all verification checks")
