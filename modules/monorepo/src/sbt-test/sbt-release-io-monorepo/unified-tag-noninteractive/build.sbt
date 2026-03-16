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

val checkFailureArtifacts =
  taskKey[Unit]("Verify unified tag conflict in non-interactive mode")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                          := "unified-tag-noninteractive-test",
    releaseIOMonorepoTagStrategy  := MonorepoTagStrategy.Unified,
    releaseIOMonorepoProcess      := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIOIgnoreUntrackedFiles := true,
    checkFailureArtifacts         := {
      val tags = "git tag".!!.trim.linesIterator.filter(_.nonEmpty).toList.sorted
      assert(
        tags == List("v1.0.0"),
        s"Expected only the pre-existing unified tag v1.0.0 but got: ${tags.mkString(", ")}"
      )

      val commitCount = "git rev-list --count HEAD".!!.trim.toInt
      assert(
        commitCount == 2,
        s"Expected initial commit plus release-version commit, found $commitCount"
      )

      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("""version := "1.0.0""""),
        s"Expected core/version.sbt to contain release version 1.0.0 but got: $coreContents"
      )
      assert(
        !coreContents.contains("1.1.0-SNAPSHOT"),
        s"core/version.sbt should not contain next snapshot: $coreContents"
      )

      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("""version := "1.0.0""""),
        s"Expected api/version.sbt to contain release version 1.0.0 but got: $apiContents"
      )
      assert(
        !apiContents.contains("1.1.0-SNAPSHOT"),
        s"api/version.sbt should not contain next snapshot: $apiContents"
      )
    }
  )
