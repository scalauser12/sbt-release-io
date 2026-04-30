import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

name := "hook-lifecycle"

scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnablePush        := false

publishTo := Some(Resolver.file("test-repo", baseDirectory.value / "target" / "test-repo"))

def markerHook(marker: String): ReleaseHookIO =
  ReleaseHookIO.sideEffect(marker) { ctx =>
    _root_.cats.effect.IO.blocking {
      val base = Project.extract(ctx.state).get(baseDirectory)
      sbt.IO.write(base / s"$marker.marker", marker + "\n")
    }
  }

releaseIOHooksBeforeTag     := Seq(markerHook("before-tag"))
releaseIOHooksAfterTag      := Seq(markerHook("after-tag"))
releaseIOHooksBeforePublish := Seq(markerHook("before-publish"))
releaseIOHooksAfterPublish  := Seq(markerHook("after-publish"))

val checkHookMarkers = taskKey[Unit]("Check hook markers")
checkHookMarkers := {
  List("before-tag", "after-tag", "before-publish", "after-publish").foreach { marker =>
    val markerFile = baseDirectory.value / s"$marker.marker"
    assert(markerFile.exists, s"Expected marker at ${markerFile.getAbsolutePath}")
  }
}

val checkGitTag = taskKey[Unit]("Check that a git tag exists")
checkGitTag := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(tags == List("v0.1.0"), s"Unexpected tags: ${tags.mkString(", ")}")
}

val checkPublished = taskKey[Unit]("Check that publish produced files")
checkPublished := {
  val repo           = baseDirectory.value / "target" / "test-repo"
  val publishedFiles = (repo ** "*").get().filter(_.isFile)
  assert(repo.exists, s"Expected publish repo at ${repo.getAbsolutePath}")
  assert(publishedFiles.nonEmpty, s"Expected published files under ${repo.getAbsolutePath}")
}
