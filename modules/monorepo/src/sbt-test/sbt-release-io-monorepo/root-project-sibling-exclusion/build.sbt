import scala.sys.process.*

lazy val sub = (project in file("sub"))
  .settings(
    name         := "sub",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(sub)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name         := "root-proj",
    scalaVersion := "2.12.18",

    // Include root project itself alongside aggregated sub-projects
    releaseIOMonorepoProjects := thisProjectRef.value +: thisProject.value.aggregate,

    releaseIOMonorepoDetectChanges := true,

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" ||
      step.name == "publish-artifacts" ||
      step.name == "run-clean" ||
      step.name == "run-tests"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkAll := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      // After release: original 2 tags + sub/v0.2.0 (root unchanged)
      assert(
        tags.length == 3,
        s"Expected 3 tags but found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(
        tags.toList == List("root/v0.1.0", "sub/v0.1.0", "sub/v0.2.0"),
        s"Expected [root/v0.1.0, sub/v0.1.0, sub/v0.2.0] but got [${tags.mkString(", ")}]"
      )

      // Root was NOT released, so its version should be unchanged
      val rootContents = IO.read(file("version.sbt"))
      assert(
        rootContents.contains("0.2.0-SNAPSHOT"),
        s"Expected root version 0.2.0-SNAPSHOT (unchanged) but got: $rootContents"
      )

      val subContents = IO.read(file("sub/version.sbt"))
      assert(
        subContents.contains("0.3.0-SNAPSHOT"),
        s"Expected sub version 0.3.0-SNAPSHOT but got: $subContents"
      )
    }
  )

val checkAll = taskKey[Unit]("Run all verification checks")
