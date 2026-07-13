import scala.sys.process.*

lazy val sub = (project in file("sub"))
  .settings(
    name         := "sub",
    scalaVersion := "2.12.18"
  )

// Loaded by sbt but intentionally omitted from the monorepo participant setting.
// Its live baseDirectory differs from Project.base; detection must exclude the
// live directory rather than the declared project location.
lazy val ignored = (project in file("ignored-declared"))
  .settings(
    name          := "ignored",
    scalaVersion  := "2.12.18",
    baseDirectory := file("ignored-live")
  )

lazy val root = (project in file("."))
  .aggregate(sub)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name         := "root-proj",
    scalaVersion := "2.12.18",

    // Include root project itself alongside aggregated sub-projects. `ignored`
    // is loaded but not aggregated, so it is deliberately nonparticipating.
    releaseIOMonorepoSelectionProjects := thisProjectRef.value +: thisProject.value.aggregate,

    releaseIOMonorepoDetectionEnabled := true,

    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,

    releaseIOVcsIgnoreUntrackedFiles := true,

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
