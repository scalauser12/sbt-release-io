import scala.sys.process._
import _root_.io.release.ReleaseStepIO
import _root_.sbtrelease.ReleaseStateTransformations.runClean

name := "tasks-as-steps-test"
scalaVersion := "2.12.18"
releaseIgnoreUntrackedFiles := true

// Marker task for fromTask
val createTaskMarker = taskKey[Unit]("Creates a file via fromTask")
createTaskMarker := IO.touch(file("task-marker"))

// Marker task for fromCommand
val createCommandMarker = taskKey[Unit]("Creates a file via sbt task run from a command")
createCommandMarker := IO.touch(file("command-marker"))

// Test all factory methods and both implicit conversions in a single release process:
//   fromTask              — via releaseIOStepTask
//   fromTaskAggregated    — via releaseIOStepTaskAggregated (uses built-in `update` task)
//   fromCommand           — via releaseIOStepCommand
//   fromCommandAndRemaining — via releaseIOStepCommandAndRemaining
//   ReleaseStep implicit  — runClean converted via sbtReleaseStepConversion
releaseIOProcess := Seq(
  releaseIOStepTask(createTaskMarker),
  releaseIOStepTaskAggregated(update),   // verifies fromTaskAggregated doesn't crash
  releaseIOStepCommand("createCommandMarker"),
  releaseIOStepCommandAndRemaining("show version"),
  runClean // implicit ReleaseStep => ReleaseStepIO via autoImport
)
