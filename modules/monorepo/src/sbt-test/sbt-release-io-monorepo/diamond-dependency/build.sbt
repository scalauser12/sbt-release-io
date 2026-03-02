import scala.sys.process._

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

val checkAll = taskKey[Unit]("Run all verification checks")

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
        before ++ Seq(RecordOrderStep.step) ++ after
      } else {
        filtered :+ RecordOrderStep.step
      }
    },
    releaseIgnoreUntrackedFiles := true,
    checkAll                    := {
      // Check execution order
      val marker = baseDirectory.value / "order.txt"
      assert(marker.exists, s"order.txt marker file does not exist at ${marker.getAbsolutePath}")
      val lines  = IO.readLines(marker).filter(_.nonEmpty)
      assert(
        lines.length == 4,
        s"Expected 4 entries but got ${lines.length}: ${lines.mkString(", ")}"
      )
      assert(lines.head == "base", s"Expected 'base' first but got '${lines.head}'")
      assert(lines.last == "top", s"Expected 'top' last but got '${lines.last}'")
      val middle = lines.slice(1, 3).sorted
      assert(
        middle == List("left", "right"),
        s"Expected middle entries [left, right] but got [${middle.mkString(", ")}]"
      )

      // Check git tags
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 4, s"Expected 4 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("base/v0.1.0", "left/v0.1.0", "right/v0.1.0", "top/v0.1.0"),
        s"Expected tags [base/v0.1.0, left/v0.1.0, right/v0.1.0, top/v0.1.0] but got [${tags.mkString(", ")}]"
      )
    }
  )
