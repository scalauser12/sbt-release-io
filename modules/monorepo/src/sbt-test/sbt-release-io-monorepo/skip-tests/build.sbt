import scala.sys.process.*
import _root_.io.release.monorepo.MonorepoStepIO

val releaseTestTask = taskKey[Unit]("Fixture-local test task used by the monorepo release step")

lazy val core = (project in file("core"))
  .settings(
    name            := "core",
    scalaVersion    := "2.12.18",
    // If tests run, this writes a marker file proving tests were NOT skipped
    releaseTestTask := {
      val marker = baseDirectory.value / "marker" / "tests-ran"
      IO.write(marker, "core tests ran")
    }
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name            := "api",
    scalaVersion    := "2.12.18",
    releaseTestTask := {
      val marker = baseDirectory.value / "marker" / "tests-ran"
      IO.write(marker, "api tests ran")
    }
  )

val checkTestsSkipped = taskKey[Unit]("Verify tests were skipped")
val checkGitTags      = taskKey[Unit]("Check git tags")
val runReleaseTests   = MonorepoStepIO.PerProject(
  name = "run-tests",
  execute = (ctx, project) =>
    if (ctx.skipTests)
      _root_.cats.effect.IO.pure(ctx)
    else
      _root_.cats.effect.IO.blocking {
        val extracted = sbt.Project.extract(ctx.state)
        val newState  = extracted.runAggregated(project.ref / releaseTestTask, ctx.state)
        ctx.withState(newState)
      }
)

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                        := "skip-tests-test",
    releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value
      .map(step => if (step.name == "run-tests") runReleaseTests else step)
      .filterNot(step => step.name == "push-changes" || step.name == "publish-artifacts"),
    releaseIOIgnoreUntrackedFiles := true,
    checkTestsSkipped           := {
      val coreMarker = file("core/marker/tests-ran")
      val apiMarker  = file("api/marker/tests-ran")
      assert(
        !coreMarker.exists(),
        s"core tests should have been skipped but marker exists at $coreMarker"
      )
      assert(
        !apiMarker.exists(),
        s"api tests should have been skipped but marker exists at $apiMarker"
      )
    },
    checkGitTags                := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("api/v0.1.0", "core/v0.1.0"),
        s"Expected tags [api/v0.1.0, core/v0.1.0] but got [${tags.mkString(", ")}]"
      )
    }
  )
