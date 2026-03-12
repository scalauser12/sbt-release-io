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
    crossScalaVersions := Seq.empty,
    markerScalaVersion := scalaVersion.value
  )

val checkFailureArtifacts =
  taskKey[Unit]("Verify empty crossScalaVersions fails release before api marker/tag/commit steps")
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
    name := "cross-build-empty-cross-test",

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

    checkFailureArtifacts := {
      val coreBase         = file("core/marker")
      val coreInvocationsF = coreBase / "invocations.txt"
      if (coreInvocationsF.exists()) {
        val coreInvocations = IO.readLines(coreInvocationsF)
        assert(
          coreInvocations.length == 2,
          s"if core marker exists, it should have 2 cross-build invocations but had ${coreInvocations.length}: $coreInvocations"
        )
      }

      val apiBase        = file("api/marker")
      val apiInvocations = apiBase / "invocations.txt"
      assert(
        !apiInvocations.exists(),
        "api marker should not be written because api has empty crossScalaVersions"
      )
      assert(!(apiBase / s"built-$Scala212").exists(), s"api should not build with $Scala212")
      assert(!(apiBase / s"built-$Scala213").exists(), s"api should not build with $Scala213")

      val tags = "git tag".!!.trim.linesIterator.filter(_.nonEmpty).toList
      assert(tags.isEmpty, s"No tags expected after failure, found: ${tags.mkString(", ")}")

      val commitCount = "git rev-list --count HEAD".!!.trim.toInt
      assert(commitCount == 1, s"Expected only initial commit after failure, found $commitCount")
    }
  )
