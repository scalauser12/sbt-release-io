import scala.sys.process.*
import _root_.io.release.monorepo.MonorepoProjectHookIO

val Scala213 = "2.13.12"
val Scala212 = "2.12.18"

// scalaVersion set only at ThisBuild — tests GlobalScope fallback for restoration
ThisBuild / scalaVersion := Scala213

lazy val core = (project in file("core"))
  .settings(
    name               := "core",
    crossScalaVersions := Seq(Scala213, Scala212),
    publishTo          := Some(Resolver.file("test-repo", file("repo")))
  )

// api does NOT cross-build — it should see the restored entry version after core's cross-build
lazy val api = (project in file("api"))
  .settings(
    name               := "api",
    crossScalaVersions := Seq(Scala213),
    publishTo          := Some(Resolver.file("test-repo", file("repo")))
  )

val checkAll             = taskKey[Unit]("Run all verification checks")
val crossBuildMarkerHook = MonorepoProjectHookIO.sideEffect("write-cross-markers") { (project, ctx) =>
  _root_.cats.effect.IO.blocking {
    val extracted = sbt.Project.extract(ctx.state)
    val sv        = extracted.get(project.ref / scalaVersion)
    val markerDir = project.baseDir / "marker"
    IO.createDirectory(markerDir)
    IO.touch(markerDir / s"built-$sv")
    IO.append(markerDir / "invocations.txt", sv + "\n")
  }
}

val checkRestoredVersionHook = MonorepoProjectHookIO.sideEffect("check-restored-version") {
  (project, ctx) =>
    _root_.cats.effect.IO.blocking {
      val extracted = sbt.Project.extract(ctx.state)
      val sv        = extracted.get(project.ref / scalaVersion)
      val markerDir = project.baseDir / "marker"
      IO.createDirectory(markerDir)
      IO.append(markerDir / "restored-version.txt", sv + "\n")
    }
}

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "cross-build-restore-test",

    releaseIOMonorepoBehaviorCrossBuild          := true,
    releaseIOMonorepoHooksBeforePublish          := Seq(crossBuildMarkerHook),
    releaseIOMonorepoHooksBeforeNextVersionWrite := Seq(checkRestoredVersionHook),
    releaseIOVcsIgnoreUntrackedFiles             := true,
    releaseIOMonorepoPolicyEnablePush            := false,
    releaseIOMonorepoPolicyEnableRunClean        := false,
    releaseIOMonorepoPolicyEnableRunTests        := false,

    checkAll := {
      // core was cross-built with both 2.13 and 2.12
      val coreMarker = file("core/marker")
      assert(
        (coreMarker / s"built-$Scala213").exists(),
        s"core should have been cross-built with $Scala213"
      )
      assert(
        (coreMarker / s"built-$Scala212").exists(),
        s"core should have been cross-built with $Scala212"
      )

      // After core's cross-build, session scalaVersion must be restored to 2.13 (the entry version).
      // The check-restored-version step records the version AFTER cross-build restoration.
      val coreRestored = IO.readLines(coreMarker / "restored-version.txt").filter(_.nonEmpty)
      assert(
        coreRestored == List(Scala213),
        s"core: session scalaVersion after cross-build should be $Scala213 but was: $coreRestored"
      )

      // api was built with only 2.13 (its single cross version).
      // Critically, if core's cross-build leaked 2.12, api would see the wrong version.
      val apiMarker = file("api/marker")
      assert(
        (apiMarker / s"built-$Scala213").exists(),
        s"api should have been built with $Scala213"
      )
      assert(
        !(apiMarker / s"built-$Scala212").exists(),
        s"api should NOT have been built with $Scala212 (would indicate leaked state)"
      )

      // api's restored version should also be 2.13
      val apiRestored = IO.readLines(apiMarker / "restored-version.txt").filter(_.nonEmpty)
      assert(
        apiRestored == List(Scala213),
        s"api: session scalaVersion after cross-build should be $Scala213 but was: $apiRestored"
      )

      // Release completed
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
    }
  )
