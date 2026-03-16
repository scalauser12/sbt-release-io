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

val checkAll = taskKey[Unit]("Run all verification checks")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                            := "custom-detector-error-test",
    // Custom change detector: throws for "api", succeeds for "core"
    releaseIOMonorepoChangeDetector := Some { (ref: ProjectRef, _: File, _: State) =>
      if (ref.project == "api")
        _root_.cats.effect.IO.raiseError(
          new RuntimeException("Simulated detector failure for api")
        )
      else
        _root_.cats.effect.IO.pure(true)
    },
    releaseIOMonorepoProcess        := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIOIgnoreUntrackedFiles     := true,
    checkAll                        := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      // Both should be tagged: core detected as changed, api conservatively treated as changed
      assert(
        tags.length == 2,
        s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(
        tags.toList == List("api/v1.0.0", "core/v1.0.0"),
        s"Expected [api/v1.0.0, core/v1.0.0] but got [${tags.mkString(", ")}]"
      )

      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("1.1.0-SNAPSHOT"),
        s"Expected core version 1.1.0-SNAPSHOT but got: $coreContents"
      )

      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("1.1.0-SNAPSHOT"),
        s"Expected api version 1.1.0-SNAPSHOT but got: $apiContents"
      )
    }
  )
