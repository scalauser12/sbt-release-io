import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

name := "hook-disabled-phases"

scalaVersion := "2.12.18"

libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test
testFrameworks += new TestFramework("munit.Framework")

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnablePush        := false

def markerHook(marker: String): ReleaseHookIO =
  ReleaseHookIO.action(marker) { ctx =>
    _root_.cats.effect.IO.blocking {
      val base = Project.extract(ctx.state).get(baseDirectory)
      sbt.IO.write(base / s"$marker.marker", marker + "\n")
    }
  }

releaseIOHooksBeforePublish := Seq(markerHook("before-publish"))
releaseIOHooksAfterPublish  := Seq(markerHook("after-publish"))

val checkNoPublishHooks = taskKey[Unit]("Check publish hooks did not run")
checkNoPublishHooks := {
  List("before-publish", "after-publish").foreach { marker =>
    val markerFile = baseDirectory.value / s"$marker.marker"
    assert(!markerFile.exists, s"Did not expect marker at ${markerFile.getAbsolutePath}")
  }
}

val checkGitTag = taskKey[Unit]("Check that a git tag exists")
checkGitTag := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(tags == List("v0.1.0"), s"Unexpected tags: ${tags.mkString(", ")}")
}
