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
    name                          := "unified-tag-version-mismatch-test",
    releaseIOMonorepoTagStrategy  := MonorepoTagStrategy.Unified,
    releaseIOMonorepoProcess      := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },
    releaseIOIgnoreUntrackedFiles := true,
    checkFailureArtifacts         := {
      // Early validation now catches the mismatch before any version writes or commits
      val commitCount = "git rev-list --count HEAD".!!.trim.toInt
      assert(
        commitCount == 1,
        s"Expected only the initial commit (early validation should prevent release-version commit), found $commitCount"
      )

      val tags = "git tag".!!.trim.linesIterator.filter(_.nonEmpty).toList
      assert(
        tags.isEmpty,
        s"Expected no tags after unified tag version mismatch, found: ${tags.mkString(", ")}"
      )

      // Version files should remain at their original SNAPSHOT values
      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("1.0.0-SNAPSHOT"),
        s"Expected core/version.sbt to still contain original SNAPSHOT but got: $coreContents"
      )

      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("1.0.0-SNAPSHOT"),
        s"Expected api/version.sbt to still contain original SNAPSHOT but got: $apiContents"
      )
    }
  )

val checkFailureArtifacts =
  taskKey[Unit](
    "Verify unified tag version mismatch fails at validate-versions before any version writes or commits"
  )
