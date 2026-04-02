import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

lazy val core = (project in file("core"))
  .settings(
    name      := "core",
    scalaVersion := "2.12.18",
    publishTo := Some(Resolver.file("test-repo", baseDirectory.value / "target" / "test-repo"))
  )

val checkHookMarkers = taskKey[Unit]("Check hook markers")
val checkGitTag      = taskKey[Unit]("Check that a git tag exists")
val checkPublished   = taskKey[Unit]("Check that publish produced files")

def markerHook(marker: String): MonorepoProjectHookIO =
  MonorepoProjectHookIO.action(marker) { (_, project) =>
    IO.blocking {
      sbt.IO.write(project.baseDir / s"$marker.marker", marker + "\n")
    }
  }

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                          := "hook-lifecycle-monorepo",
    releaseIOVcsIgnoreUntrackedFiles := true,
    releaseIOMonorepoPolicyEnablePush   := false,
    releaseIOMonorepoHooksBeforeTag := Seq(markerHook("before-tag")),
    releaseIOMonorepoHooksAfterTag := Seq(markerHook("after-tag")),
    releaseIOMonorepoHooksBeforePublish := Seq(markerHook("before-publish")),
    releaseIOMonorepoHooksAfterPublish := Seq(markerHook("after-publish")),
    checkHookMarkers              := {
      List("before-tag", "after-tag", "before-publish", "after-publish").foreach { marker =>
        val markerFile = file("core") / s"$marker.marker"
        assert(markerFile.exists, s"Expected marker at ${markerFile.getAbsolutePath}")
      }
    },
    checkGitTag                   := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
      assert(tags == List("core/v0.1.0"), s"Unexpected tags: ${tags.mkString(", ")}")
    },
    checkPublished                := {
      val repo           = file("core") / "target" / "test-repo"
      val publishedFiles = (repo ** "*").get().filter(_.isFile)
      assert(repo.exists, s"Expected publish repo at ${repo.getAbsolutePath}")
      assert(publishedFiles.nonEmpty, s"Expected published files under ${repo.getAbsolutePath}")
    }
  )
