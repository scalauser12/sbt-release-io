import scala.sys.process._

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

    releaseIgnoreUntrackedFiles := true,

    checkTags := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      // After release: original 2 tags + root-v0.2.0 (sub unchanged)
      // Tag uses sbt project ID "root", not name setting "root-proj"
      assert(
        tags.length == 3,
        s"Expected 3 tags but found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(
        tags.toList == List("root-v0.1.0", "root-v0.2.0", "sub-v0.1.0"),
        s"Expected [root-v0.1.0, root-v0.2.0, sub-v0.1.0] but got [${tags.mkString(", ")}]"
      )
    },

    checkRootVersion := {
      val contents = IO.read(file("version.sbt"))
      assert(
        contents.contains("0.3.0-SNAPSHOT"),
        s"Expected root version 0.3.0-SNAPSHOT but got: $contents"
      )
    },

    checkSubVersion := {
      val contents = IO.read(file("sub/version.sbt"))
      // sub was NOT released, so its version should be unchanged
      assert(
        contents.contains("0.2.0-SNAPSHOT"),
        s"Expected sub version 0.2.0-SNAPSHOT (unchanged) but got: $contents"
      )
    }
  )

val checkTags        = taskKey[Unit]("Check git tags")
val checkRootVersion = taskKey[Unit]("Check root version.sbt")
val checkSubVersion  = taskKey[Unit]("Check sub version.sbt")
