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

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                         := "unified-tag-version-mismatch-test",
    releaseIOMonorepoTagStrategy := MonorepoTagStrategy.Unified,
    releaseIOMonorepoProcess     := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },
    releaseIOIgnoreUntrackedFiles  := true,
    checkFailureArtifacts        := {
      val commitCount = "git rev-list --count HEAD".!!.trim.toInt
      assert(
        commitCount == 2,
        s"Expected initial commit plus release-version commit after unified tag version mismatch, found $commitCount"
      )

      val tags = "git tag".!!.trim.linesIterator.filter(_.nonEmpty).toList
      assert(tags.isEmpty, s"Expected no tags after unified tag version mismatch, found: ${tags.mkString(", ")}")

      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("""version := "1.0.0""""),
        s"Expected core/version.sbt to contain release version 1.0.0 after unified tag version mismatch but got: $coreContents"
      )
      assert(
        !coreContents.contains("1.1.0-SNAPSHOT"),
        s"core/version.sbt should not contain next snapshot after unified tag version mismatch: $coreContents"
      )

      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("""version := "2.0.0""""),
        s"Expected api/version.sbt to contain release version 2.0.0 after unified tag version mismatch but got: $apiContents"
      )
      assert(
        !apiContents.contains("2.1.0-SNAPSHOT"),
        s"api/version.sbt should not contain next snapshot after unified tag version mismatch: $apiContents"
      )
    }
  )

val checkFailureArtifacts =
  taskKey[Unit]("Verify unified tag version mismatch fails after release-version commit but before any tag or next-version writes")
