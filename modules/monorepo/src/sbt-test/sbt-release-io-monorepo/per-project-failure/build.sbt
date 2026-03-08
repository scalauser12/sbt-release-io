import scala.sys.process._
import _root_.io.release.monorepo.MonorepoStepIO

val releaseTestTask = taskKey[Unit]("Fixture-local test task used by the monorepo release step")

lazy val core = (project in file("core"))
  .settings(
    name            := "core",
    scalaVersion    := "2.12.18",
    releaseTestTask := { throw new RuntimeException("core tests intentionally fail") }
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name            := "api",
    scalaVersion    := "2.12.18",
    releaseTestTask := ()
  )

val checkNoTags     = taskKey[Unit]("Verify no release tags were created")
val runReleaseTests = MonorepoStepIO.PerProject(
  name = "run-tests",
  action = (ctx, project) =>
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
    name                        := "per-project-failure-test",
    // Keep run-tests in the process to trigger failure. Filter push/publish.
    releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value
      .map(step => if (step.name == "run-tests") runReleaseTests else step)
      .filterNot(step => step.name == "push-changes" || step.name == "publish-artifacts"),
    releaseIgnoreUntrackedFiles := true,
    checkNoTags                 := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      // After failure, all subsequent steps (including tagging) should be skipped
      assert(tags.isEmpty, s"Expected no tags after failure but found: ${tags.mkString(", ")}")
    }
  )
