import scala.sys.process._
import _root_.io.release.monorepo.{MonorepoStepIO, MonorepoContext, ProjectReleaseInfo}

lazy val base = (project in file("base"))
  .settings(name := "base", scalaVersion := "2.12.18")

lazy val left = (project in file("left"))
  .dependsOn(base)
  .settings(name := "left", scalaVersion := "2.12.18")

lazy val right = (project in file("right"))
  .dependsOn(base)
  .settings(name := "right", scalaVersion := "2.12.18")

lazy val top = (project in file("top"))
  .dependsOn(left, right)
  .settings(name := "top", scalaVersion := "2.12.18")

val recordOrder: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
  name = "record-order",
  action = (ctx: MonorepoContext, project: ProjectReleaseInfo) =>
    _root_.cats.effect.IO {
      val rootBase = sbt.Project.extract(ctx.state).get(baseDirectory)
      val marker   = new java.io.File(rootBase, "order.txt")
      val writer   = new java.io.FileWriter(marker, true)
      writer.write(project.name + "\n")
      writer.close()
      ctx
    }
)

val checkOrder   = taskKey[Unit]("Check project execution order in diamond dependency")
val checkGitTags = taskKey[Unit]("Check git tags for diamond dependency")

lazy val root = (project in file("."))
  .aggregate(base, left, right, top)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                        := "diamond-dependency-test",
    releaseIOMonorepoProcess    := {
      val defaultSteps = releaseIOMonorepoProcess.value
      val filtered     = defaultSteps.filterNot { step =>
        step.name == "push-changes" || step.name == "publish-artifacts" ||
        step.name == "run-clean" || step.name == "run-tests"
      }
      val idx          = filtered.indexWhere(_.name == "resolve-release-order")
      if (idx >= 0) {
        val (before, after) = filtered.splitAt(idx + 1)
        before ++ Seq(recordOrder) ++ after
      } else {
        filtered :+ recordOrder
      }
    },
    releaseIgnoreUntrackedFiles := true,
    checkOrder                  := {
      val marker = baseDirectory.value / "order.txt"
      assert(marker.exists, s"order.txt marker file does not exist at ${marker.getAbsolutePath}")
      val lines  = IO.readLines(marker).filter(_.nonEmpty)
      assert(
        lines.length == 4,
        s"Expected 4 entries but got ${lines.length}: ${lines.mkString(", ")}"
      )
      // base must be first (both left and right depend on it)
      assert(lines.head == "base", s"Expected 'base' first but got '${lines.head}'")
      // top must be last (depends on both left and right)
      assert(lines.last == "top", s"Expected 'top' last but got '${lines.last}'")
      // middle two must be left and right in either order
      val middle = lines.slice(1, 3).sorted
      assert(
        middle == List("left", "right"),
        s"Expected middle entries [left, right] but got [${middle.mkString(", ")}]"
      )
    },
    checkGitTags                := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 4, s"Expected 4 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("base-v0.1.0", "left-v0.1.0", "right-v0.1.0", "top-v0.1.0"),
        s"Expected tags [base-v0.1.0, left-v0.1.0, right-v0.1.0, top-v0.1.0] but got [${tags.mkString(", ")}]"
      )
    }
  )
