import scala.sys.process.*
import _root_.io.release.monorepo.MonorepoStepIO

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val middle = (project in file("middle"))
  .dependsOn(core)
  .settings(
    name         := "middle",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .dependsOn(middle)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

val checkAll = taskKey[Unit]("Run all verification checks")
val recordOrderStep = MonorepoStepIO.PerProject(
  name = "record-order",
  execute = (ctx, project) =>
    _root_.cats.effect.IO.blocking {
      val writer = new java.io.FileWriter(file("order.txt"), true)
      writer.write(project.name + "\n")
      writer.close()
      ctx
    }
)

lazy val root = (project in file("."))
  .aggregate(core, middle, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "topological-order-test",

    // Insert record-order step right after resolve-release-order,
    // and remove push/publish/clean/test steps
    releaseIOMonorepoProcess := {
      val defaultSteps = releaseIOMonorepoProcess.value
      val filtered     = defaultSteps.filterNot { step =>
        step.name == "push-changes" ||
        step.name == "publish-artifacts" ||
        step.name == "run-clean" ||
        step.name == "run-tests"
      }
      val idx          = filtered.indexWhere(_.name == "resolve-release-order")
      if (idx >= 0) {
        val (before, after) = filtered.splitAt(idx + 1)
        before ++ Seq(recordOrderStep) ++ after
      } else {
        filtered :+ recordOrderStep
      }
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkAll := {
      // Check execution order
      val marker = baseDirectory.value / "order.txt"
      assert(marker.exists, s"order.txt marker file does not exist at ${marker.getAbsolutePath}")
      val lines  = IO.readLines(marker).filter(_.nonEmpty)
      // Topological order: core (no deps) -> middle (depends on core) -> api (depends on middle)
      assert(
        lines == List("core", "middle", "api"),
        s"Expected order [core, middle, api] but got [${lines.mkString(", ")}]"
      )

      // Check git tags
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 3, s"Expected 3 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("api/v0.1.0", "core/v0.1.0", "middle/v0.1.0"),
        s"Expected tags [api/v0.1.0, core/v0.1.0, middle/v0.1.0] but got [${tags.mkString(", ")}]"
      )
    }
  )
