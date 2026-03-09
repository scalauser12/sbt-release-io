import scala.sys.process._

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18",
    publishTo    := Some(Resolver.file("test-repo", baseDirectory.value / "repo"))
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
    // NOTE: publishTo is intentionally NOT set.
  )

val checkSelectionAwareValidation =
  taskKey[Unit]("Verify unselected projects are not validated after selection")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "selection-aware-validation-test",
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIgnoreUntrackedFiles := true,
    checkSelectionAwareValidation := {
      val tags        = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
      val coreVersion = IO.read(file("core/version.sbt"))
      val apiVersion  = IO.read(file("api/version.sbt"))

      assert(tags == List("core/v1.0.0"), s"Unexpected tags: ${tags.mkString(", ")}")
      assert(
        coreVersion.contains("""version := "1.1.0-SNAPSHOT""""),
        s"Expected core to advance to the next version, but got: $coreVersion"
      )
      assert(
        apiVersion.contains("""version := "0.2.0-SNAPSHOT""""),
        s"Expected api to remain unchanged, but got: $apiVersion"
      )
    }
  )
