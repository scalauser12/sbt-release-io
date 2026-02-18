import scala.sys.process._
import _root_.io.release.ReleaseStepIO
import _root_.sbtrelease.ReleaseStateTransformations.runClean

scalaVersion := "2.12.18"

// Task that creates aggregated-marker in the project base dir — shared by root and sub
val createAggregatedMarker = taskKey[Unit]("Creates aggregated-marker via fromTaskAggregated")

def aggregatedMarkerSetting = createAggregatedMarker := IO.touch(baseDirectory.value / "aggregated-marker")

// Root-only tasks
val createTaskMarker = taskKey[Unit]("Creates a file via fromTask")
val createCommandMarker = taskKey[Unit]("Creates a file via sbt task run from a command")
val createInputTaskMarker = inputKey[Unit]("Creates a file via fromInputTask")

lazy val root = (project in file("."))
  .aggregate(sub)
  .settings(
    name := "tasks-as-steps-test",
    releaseIgnoreUntrackedFiles := true,
    aggregatedMarkerSetting,
    createTaskMarker := IO.touch(file("task-marker")),
    createCommandMarker := IO.touch(file("command-marker")),
    createInputTaskMarker := {
      import sbt.complete.DefaultParsers._
      val fileName = spaceDelimited("<filename>").parsed.headOption.getOrElse("inputtask-marker")
      IO.touch(file(fileName))
    },
    // Test all factory methods and both implicit conversions in a single release process:
    //   fromTask              — via releaseIOStepTask
    //   fromTaskAggregated    — via releaseIOStepTaskAggregated (root + sub artifacts)
    //   fromInputTask         — via releaseIOStepInputTask (default + explicit args)
    //   fromCommand           — via releaseIOStepCommand
    //   fromCommandAndRemaining — via releaseIOStepCommandAndRemaining
    //   ReleaseStep implicit  — runClean converted via sbtReleaseStepConversion
    releaseIOProcess := Seq(
      releaseIOStepTask(createTaskMarker),
      releaseIOStepTaskAggregated(createAggregatedMarker),
      releaseIOStepInputTask(createInputTaskMarker),
      releaseIOStepInputTask(createInputTaskMarker, " custom-input-marker"),
      releaseIOStepCommand("createCommandMarker"),
      releaseIOStepCommandAndRemaining("show version"),
      runClean
    )
  )

lazy val sub = (project in file("sub"))
  .settings(
    name := "tasks-as-steps-sub",
    scalaVersion := "2.12.18",
    aggregatedMarkerSetting
  )
