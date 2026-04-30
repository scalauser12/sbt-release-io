import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

lazy val core = (project in file("core"))
  .settings(
    name                                   := "core",
    scalaVersion                           := "2.12.18",
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )

val checkNoPublishHooks = taskKey[Unit]("Check publish hooks did not run")
val checkGitTag         = taskKey[Unit]("Check that a git tag exists")

def markerHook(marker: String): MonorepoProjectHookIO =
  MonorepoProjectHookIO.sideEffect(marker) { (project, _) =>
    IO.blocking {
      sbt.IO.write(project.baseDir / s"$marker.marker", marker + "\n")
    }
  }

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "hook-disabled-phases-monorepo",
    releaseIOVcsIgnoreUntrackedFiles      := true,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoHooksBeforePublish   := Seq(markerHook("before-publish")),
    releaseIOMonorepoHooksAfterPublish    := Seq(markerHook("after-publish")),
    checkNoPublishHooks                   := {
      List("before-publish", "after-publish").foreach { marker =>
        val markerFile = file("core") / s"$marker.marker"
        assert(!markerFile.exists, s"Did not expect marker at ${markerFile.getAbsolutePath}")
      }
    },
    checkGitTag                           := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
      assert(tags == List("core/v0.1.0"), s"Unexpected tags: ${tags.mkString(", ")}")
    }
  )
