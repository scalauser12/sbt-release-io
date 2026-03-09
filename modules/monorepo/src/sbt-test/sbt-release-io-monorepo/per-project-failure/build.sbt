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

val checkFailureArtifacts = taskKey[Unit]("Verify run-tests failure stops all later release mutations")
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
    checkFailureArtifacts       := {
      val commitCount = "git rev-list --count HEAD".!!.trim.toInt
      assert(commitCount == 1, s"Expected only the initial commit after failure, found $commitCount")

      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(tags.isEmpty, s"Expected no tags after failure but found: ${tags.mkString(", ")}")

      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("""version := "0.1.0-SNAPSHOT""""),
        s"Expected core/version.sbt to remain at 0.1.0-SNAPSHOT but got: $coreContents"
      )

      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("""version := "0.1.0-SNAPSHOT""""),
        s"Expected api/version.sbt to remain at 0.1.0-SNAPSHOT but got: $apiContents"
      )
    }
  )
