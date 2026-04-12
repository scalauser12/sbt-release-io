import scala.sys.process.*
import _root_.io.release.monorepo.MonorepoReleasePlugin
import _root_.io.release.monorepo.MonorepoReleasePlugin.autoImport.*

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

val checkAll = taskKey[Unit]("Run all verification checks")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(MonorepoSharedSettings.sharedSettings)
  .settings(
    releaseIOMonorepoPolicyEnablePublish := false,
    releaseIOMonorepoPolicyEnablePush    := false,
    name                                 := "project-scala-shared-import-test",
    checkAll                             := {
      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("0.2.0-SNAPSHOT"),
        s"Expected core version 0.2.0-SNAPSHOT in core/version.sbt but got: $coreContents"
      )

      val expectedCommits = 3
      val actualCommits   = "git log --oneline".!!.trim.linesIterator.length
      assert(
        actualCommits == expectedCommits,
        s"Expected $expectedCommits commits but found $actualCommits"
      )

      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList.sorted
      assert(
        tags == List("core/v0.1.0"),
        s"Expected [core/v0.1.0] but got [${tags.mkString(", ")}]"
      )

      assert(file("ignored-untracked.txt").exists(), "Expected ignored-untracked.txt to remain")
    }
  )
