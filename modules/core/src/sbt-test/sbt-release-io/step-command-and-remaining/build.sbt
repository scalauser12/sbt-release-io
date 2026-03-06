import scala.sys.process._

name := "step-command-and-remaining-test"

scalaVersion := "2.12.18"

releaseIgnoreUntrackedFiles := true

// Minimal process: only stepCommandAndRemaining to verify the factory works
releaseIOProcess := Seq(stepCommandAndRemaining("compile"))
