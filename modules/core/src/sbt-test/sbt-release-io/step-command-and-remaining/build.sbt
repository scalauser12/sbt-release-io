import _root_.io.release.ReleaseStepIO
import sbt.*

name := "step-command-and-remaining-test"

scalaVersion := "2.12.18"

releaseIOIgnoreUntrackedFiles := true

val writeCompileMarker = taskKey[Unit]("Write a marker after compile drains from the command queue")
writeCompileMarker := {
  val markerDir = baseDirectory.value / "marker"
  val marker    = markerDir / "compile-ran"
  IO.createDirectory(markerDir)
  IO.touch(marker)
}

lazy val compileAndMark = Command.command("compileAndMark") { state =>
  state.copy(
    remainingCommands = Exec("compile", None) ::
      Exec("writeCompileMarker", None) ::
      state.remainingCommands
  )
}

commands += compileAndMark

// Minimal process: only ReleaseStepIO.fromCommandAndRemaining to verify the command drains queued commands
releaseIOProcess := Seq(ReleaseStepIO.fromCommandAndRemaining("compileAndMark"))
