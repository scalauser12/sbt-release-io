import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

val Scala213 = "2.13.12"
val Scala212 = "2.12.18"

name := "cross-test"

scalaVersion := Scala213

crossScalaVersions := Seq(Scala213, Scala212)

libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test
testFrameworks += new TestFramework("munit.Framework")

releaseIOEnablePush    := false

releaseIOIgnoreUntrackedFiles := true
publishTo := Some(Resolver.file("test-repo", baseDirectory.value / "target" / "test-repo"))

val crossBuildMarkerHook = ReleaseHookIO.action("write-cross-markers") { ctx =>
  _root_.cats.effect.IO.blocking {
    val base = Project.extract(ctx.state).get(baseDirectory)
    val sv   = Project.extract(ctx.state).get(scalaVersion)
    IO.createDirectory(base / "marker")
    IO.touch(base / "marker" / s"built-$sv")
    IO.append(base / "marker" / "invocations.txt", sv + "\n")
  }
}

releaseIOBeforePublishHooks := Seq(crossBuildMarkerHook)

val checkGitTag = taskKey[Unit]("Check that a git tag exists")
checkGitTag := {
  val tags = "git tag".!!.trim
  assert(tags.nonEmpty, "Expected at least one git tag but found none")
}
