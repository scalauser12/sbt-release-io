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

lazy val extra = (project in file("extra"))
  .settings(
    name         := "extra",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core, api, extra)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "custom-projects-setting-test",

    // Override to release only core and api - exclude extra
    releaseIOMonorepoProjects := thisProject.value.aggregate.filter { ref =>
      ref.project == "core" || ref.project == "api"
    },

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkAll := {
      // core and api should be updated
      val coreContents  = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("0.2.0-SNAPSHOT"),
        s"Expected core 0.2.0-SNAPSHOT but got: $coreContents"
      )
      val apiContents   = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("0.2.0-SNAPSHOT"),
        s"Expected api 0.2.0-SNAPSHOT but got: $apiContents"
      )
      // extra should be unchanged (excluded from release)
      val extraContents = IO.read(file("extra/version.sbt"))
      assert(
        extraContents.contains("0.1.0-SNAPSHOT"),
        s"Expected extra unchanged 0.1.0-SNAPSHOT but got: $extraContents"
      )
      // Only 2 tags (core and api)
      val tags          = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}")
      assert(
        tags.sorted.toList == List("api/v0.1.0", "core/v0.1.0"),
        s"Expected [api/v0.1.0, core/v0.1.0] but got [${tags.sorted.mkString(", ")}]"
      )
    }
  )

val checkAll = taskKey[Unit]("Run all verification checks")
