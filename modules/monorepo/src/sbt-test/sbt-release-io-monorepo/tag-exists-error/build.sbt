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

val checkCoreTagStillExists    = taskKey[Unit]("Verify the pre-existing core tag still exists")
val checkApiTagCreated         = taskKey[Unit]("Verify api tag was created despite core failure")
val checkNextVersionNotApplied =
  taskKey[Unit]("Verify set-next-version was skipped after tag failure")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                        := "tag-exists-error-test",
    releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIgnoreUntrackedFiles := true,
    checkCoreTagStillExists     := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(
        tags.contains("core/v1.0.0"),
        s"Expected pre-existing tag core/v1.0.0 but tags are: ${tags.mkString(", ")}"
      )
    },
    checkApiTagCreated          := {
      // Per-project error isolation: core failed but api's tag should still have been created
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(
        tags.contains("api/v1.0.0"),
        s"Expected api/v1.0.0 tag (per-project isolation) but tags are: ${tags.mkString(", ")}"
      )
    },
    checkNextVersionNotApplied  := {
      // Tag failure should propagate globally and skip set-next-version.
      // Version files should contain the release version (1.0.0), not the next SNAPSHOT.
      val coreVer = IO.read(file("core/version.sbt"))
      val apiVer  = IO.read(file("api/version.sbt"))
      assert(
        !coreVer.contains("1.1.0-SNAPSHOT"),
        s"core version.sbt should not contain next SNAPSHOT after tag failure: $coreVer"
      )
      assert(
        !apiVer.contains("1.1.0-SNAPSHOT"),
        s"api version.sbt should not contain next SNAPSHOT after tag failure: $apiVer"
      )
    }
  )
