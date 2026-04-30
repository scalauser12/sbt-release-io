import scala.sys.process.*
import sbt.IO
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

val Scala213 = "2.13.12"
val Scala212 = "2.12.18"

name := "cross-build-setting-test"

scalaVersion := Scala213

crossScalaVersions := Seq(Scala213, Scala212, Scala213)

// Enable cross-build via the setting (not the CLI flag)
releaseIOBehaviorCrossBuild := true

libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test
testFrameworks += new TestFramework("munit.Framework")

releaseIOPolicyEnablePush := false

releaseIOVcsIgnoreUntrackedFiles := true
publishTo                        := Some(Resolver.file("test-repo", baseDirectory.value / "target" / "test-repo"))

val crossBuildMarkerHook = ReleaseHookIO.sideEffect("write-cross-markers") { ctx =>
  _root_.cats.effect.IO.blocking {
    val base = Project.extract(ctx.state).get(baseDirectory)
    val sv   = Project.extract(ctx.state).get(scalaVersion)
    IO.createDirectory(base / "marker")
    IO.touch(base / "marker" / s"built-$sv")
    IO.append(base / "marker" / "invocations.txt", sv + "\n")
  }
}

releaseIOHooksBeforePublish := Seq(crossBuildMarkerHook)

val checkCrossBuildInvocations =
  taskKey[Unit]("Verify cross-build ran exactly once per distinct configured Scala version")
checkCrossBuildInvocations := {
  val markerDir    = baseDirectory.value / "marker"
  val built213     = markerDir / s"built-$Scala213"
  val built212     = markerDir / s"built-$Scala212"
  val invocationsF = markerDir / "invocations.txt"
  assert(
    built213.exists(),
    s"Expected cross-build marker for $Scala213 at ${built213.getAbsolutePath}"
  )
  assert(
    built212.exists(),
    s"Expected cross-build marker for $Scala212 at ${built212.getAbsolutePath}"
  )
  assert(invocationsF.exists(), s"Expected invocation log at ${invocationsF.getAbsolutePath}")
  val invocations  = IO.readLines(invocationsF).filter(_.nonEmpty)
  assert(
    invocations.length == 2,
    s"Expected exactly 2 cross-build invocations but found ${invocations.length}: $invocations"
  )
  assert(
    invocations.sorted == List(Scala212, Scala213),
    s"Expected cross-build invocations [$Scala212, $Scala213] but got [${invocations.sorted.mkString(", ")}]"
  )
}

val checkGitTag = taskKey[Unit]("Check that the default release tag was created")
checkGitTag := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(tags.length == 1, s"Expected 1 git tag but found ${tags.length}: ${tags.mkString(", ")}")
  assert(tags.head == "v0.1.0", s"Expected git tag v0.1.0 but found ${tags.head}")
}

val checkPublished = taskKey[Unit]("Check that publish produced files")
checkPublished := {
  val repo           = baseDirectory.value / "target" / "test-repo"
  val publishedFiles = (repo ** "*").get().filter(_.isFile)
  assert(repo.exists, s"Expected publish repo at ${repo.getAbsolutePath}")
  assert(publishedFiles.nonEmpty, s"Expected published files under ${repo.getAbsolutePath}")
}

val checkNextVersion =
  taskKey[Unit]("Check that version.sbt was updated to the next snapshot version")
checkNextVersion := {
  val contents = IO.read(baseDirectory.value / "version.sbt")
  assert(
    contents.contains("""version := "0.2.0-SNAPSHOT""""),
    s"""Expected version.sbt to contain 'version := "0.2.0-SNAPSHOT"' but got: $contents"""
  )
}
