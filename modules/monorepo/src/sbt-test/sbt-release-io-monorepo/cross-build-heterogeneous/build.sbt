import scala.sys.process._

val Scala213 = "2.13.12"
val Scala212 = "2.12.18"

lazy val core = (project in file("core"))
  .settings(
    name               := "core",
    scalaVersion       := Scala213,
    crossScalaVersions := Seq(Scala213, Scala212)
  )

lazy val api = (project in file("api"))
  .settings(
    name               := "api",
    scalaVersion       := Scala212,
    crossScalaVersions := Seq(Scala212)
  )

val checkCoreMarkers = taskKey[Unit]("Check core was cross-built for both 2.12 and 2.13")
val checkApiMarkers  = taskKey[Unit]("Check api was built exactly once")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "cross-build-heterogeneous-test",

    releaseIOMonorepoProcess := {
      import _root_.io.release.monorepo.MonorepoStepIO
      import _root_.io.release.monorepo.steps.MonorepoReleaseSteps._

      // Use an atomic counter file to count invocations per project.
      // Each invocation appends a line; count lines to verify.
      Seq(
        initializeVcs,
        resolveReleaseOrder,
        detectOrSelectProjects,
        inquireVersions,
        MonorepoStepIO.PerProject(
          name = "write-cross-markers",
          action = (ctx, project) => {
            val extracted = sbt.Project.extract(ctx.state)
            val sv        = extracted.get(scalaVersion)
            // Write version-specific markers for multi-version case
            val marker    = project.baseDir / "marker" / s"built-$sv"
            sbt.IO.touch(marker)
            // Also append to invocation counter
            val counter   = project.baseDir / "marker" / "invocations.txt"
            sbt.IO.append(counter, sv + "\n")
            cats.effect.IO.pure(ctx)
          },
          enableCrossBuild = true
        ),
        setReleaseVersions,
        commitReleaseVersions,
        tagReleases,
        setNextVersions,
        commitNextVersions
      )
    },

    releaseIgnoreUntrackedFiles := true,

    // core has crossScalaVersions := Seq(2.13, 2.12) → action runs twice, once per version
    checkCoreMarkers := {
      val base        = file("core/marker")
      val has213      = (base / s"built-$Scala213").exists()
      val has212      = (base / s"built-$Scala212").exists()
      assert(has213, s"core should have been cross-built with $Scala213")
      assert(has212, s"core should have been cross-built with $Scala212")
      val invocations = IO.readLines(base / "invocations.txt")
      assert(
        invocations.length == 2,
        s"core should have 2 cross-build invocations but had ${invocations.length}: $invocations"
      )
    },

    // api has crossScalaVersions := Seq(2.12) → action runs once with correct version
    checkApiMarkers := {
      val base        = file("api/marker")
      val has212      = (base / s"built-$Scala212").exists()
      assert(has212, s"api should have been built with $Scala212")
      val has213      = (base / s"built-$Scala213").exists()
      assert(!has213, s"api should NOT have been built with $Scala213")
      val invocations = IO.readLines(base / "invocations.txt")
      assert(
        invocations.length == 1,
        s"api should have 1 cross-build invocation but had ${invocations.length}: $invocations"
      )
    }
  )
