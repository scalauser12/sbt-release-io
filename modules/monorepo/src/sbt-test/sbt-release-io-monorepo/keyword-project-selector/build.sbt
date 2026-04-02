import scala.sys.process.*

val checkKeywordProjectRelease =
  taskKey[Unit]("Verify that project <id> selects only the keyword-like project")

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val cross = (project in file("cross"))
  .settings(
    name         := "cross",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core, cross)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "keyword-project-selector-test",
    releaseIOMonorepoPolicyEnablePublish := false,
    releaseIOMonorepoPolicyEnablePush    := false,
    releaseIOVcsIgnoreUntrackedFiles := true,
    checkKeywordProjectRelease := {
      val coreContents  = IO.read(file("core/version.sbt"))
      val crossContents = IO.read(file("cross/version.sbt"))

      assert(
        coreContents.contains("0.1.0-SNAPSHOT"),
        s"Expected core/version.sbt to remain unchanged but got: $coreContents"
      )
      assert(
        crossContents.contains("0.2.0-SNAPSHOT"),
        s"Expected cross/version.sbt to advance to 0.2.0-SNAPSHOT but got: $crossContents"
      )

      val commitCount = "git log --oneline".!!.trim.linesIterator.length
      assert(commitCount == 3, s"Expected 3 commits but found $commitCount")

      val tags = "git tag".!!.trim.linesIterator.filter(_.nonEmpty).toList.sorted
      assert(tags == List("cross/v0.1.0"), s"Expected only cross/v0.1.0 but found: $tags")
    }
  )
