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

val checkTags        = taskKey[Unit]("Check git tags")
val checkCoreVersion = taskKey[Unit]("Check core version.sbt")
val checkApiVersion  = taskKey[Unit]("Check api version.sbt")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                            := "custom-change-detector-test",
    // Custom change detector: only "core" is considered changed
    releaseIOMonorepoChangeDetector := Some { (ref: ProjectRef, _: File, _: State) =>
      _root_.cats.effect.IO.pure(ref.project == "core")
    },
    releaseIOMonorepoProcess        := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIgnoreUntrackedFiles     := true,
    checkTags                       := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      // Only core should be tagged (api was not detected as changed)
      assert(
        tags.length == 1,
        s"Expected 1 tag (core only) but found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(tags.head == "core/v1.0.0", s"Expected tag 'core/v1.0.0' but got '${tags.head}'")
    },
    checkCoreVersion                := {
      val contents = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("1.1.0-SNAPSHOT"),
        s"Expected core version 1.1.0-SNAPSHOT but got: $contents"
      )
    },
    checkApiVersion                 := {
      val contents = IO.read(file("api/version.sbt"))
      // api was NOT released, so its version should still be 0.1.0-SNAPSHOT
      assert(
        contents.contains("0.1.0-SNAPSHOT"),
        s"Expected api version 0.1.0-SNAPSHOT (unchanged) but got: $contents"
      )
    }
  )
