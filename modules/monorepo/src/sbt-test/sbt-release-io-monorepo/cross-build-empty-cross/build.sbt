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
    crossScalaVersions := Seq.empty
  )

val checkFailureArtifacts =
  taskKey[Unit]("Verify empty crossScalaVersions fails release before api marker/tag/commit steps")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "cross-build-empty-cross-test",

    releaseIOMonorepoProcess := {
      import _root_.io.release.monorepo.MonorepoStepIO
      import _root_.io.release.monorepo.steps.MonorepoReleaseSteps._

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
            val marker    = project.baseDir / "marker" / s"built-$sv"
            sbt.IO.touch(marker)
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
