name := "exit-code-test"
scalaVersion := "2.12.18"

val failingTask = taskKey[Unit]("A task that will fail")
failingTask := { throw new IllegalStateException("This task always fails") }

releaseIgnoreUntrackedFiles := true
