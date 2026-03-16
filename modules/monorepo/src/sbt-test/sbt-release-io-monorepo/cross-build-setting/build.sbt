import scala.sys.process.*
import _root_.io.release.monorepo.MonorepoStepIO

val Scala213 = "2.13.12"
val Scala212 = "2.12.18"
val markerScalaVersion = taskKey[String]("Current scalaVersion used by the marker step")

lazy val core = (project in file("core"))
  .settings(
    name               := "core",
    scalaVersion       := Scala213,
    crossScalaVersions := Seq(Scala213, Scala212),
    markerScalaVersion := scalaVersion.value
  )

lazy val api = (project in file("api"))
  .settings(
    name               := "api",
    scalaVersion       := Scala212,
    crossScalaVersions := Seq(Scala212),
    markerScalaVersion := scalaVersion.value
  )

val checkAll = taskKey[Unit]("Run all verification checks")
val crossBuildMarkerStep = MonorepoStepIO.PerProject(
  name = "write-cross-markers",
  execute = (ctx, project) =>
    _root_.cats.effect.IO.blocking {
      val extracted      = sbt.Project.extract(ctx.state)
      val (newState, sv) = extracted.runTask(project.ref / markerScalaVersion, ctx.state)
      val markerDir      = project.baseDir / "marker"
      IO.touch(markerDir / s"built-$sv")
      IO.append(markerDir / "invocations.txt", sv + "\n")
      ctx.withState(newState)
    },
  enableCrossBuild = true
)

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "cross-build-setting-test",

    // Enable cross-build via the SETTING (not the CLI flag)
    releaseIOMonorepoCrossBuild := true,

    releaseIOMonorepoProcess := {
      import _root_.io.release.monorepo.steps.MonorepoReleaseSteps.*

      Seq(
        initializeVcs,
        resolveReleaseOrder,
        detectOrSelectProjects,
        inquireVersions,
        crossBuildMarkerStep,
        setReleaseVersions,
        commitReleaseVersions,
        tagReleases,
        setNextVersions,
        commitNextVersions
      )
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkAll := {
      // core has crossScalaVersions := Seq(2.13, 2.12) -> action runs twice
      val coreBase        = file("core/marker")
      val coreHas213      = (coreBase / s"built-$Scala213").exists()
      val coreHas212      = (coreBase / s"built-$Scala212").exists()
      assert(coreHas213, s"core should have been cross-built with $Scala213")
      assert(coreHas212, s"core should have been cross-built with $Scala212")
      val coreInvocations = IO.readLines(coreBase / "invocations.txt")
      assert(
        coreInvocations.length == 2,
        s"core should have 2 cross-build invocations but had ${coreInvocations.length}: $coreInvocations"
      )

      // api has crossScalaVersions := Seq(2.12) -> action runs once
      val apiBase        = file("api/marker")
      val apiHas212      = (apiBase / s"built-$Scala212").exists()
      assert(apiHas212, s"api should have been built with $Scala212")
      val apiHas213      = (apiBase / s"built-$Scala213").exists()
      assert(!apiHas213, s"api should NOT have been built with $Scala213")
      val apiInvocations = IO.readLines(apiBase / "invocations.txt")
      assert(
        apiInvocations.length == 1,
        s"api should have 1 cross-build invocation but had ${apiInvocations.length}: $apiInvocations"
      )

      // Verify release completed (tags exist)
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("api/v0.1.0", "core/v0.1.0"),
        s"Expected tags [api/v0.1.0, core/v0.1.0] but got [${tags.mkString(", ")}]"
      )
    }
  )
