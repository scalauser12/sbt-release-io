name         := "run-clean-test"
scalaVersion := "2.12.18"

val seedStaleFile          = taskKey[Unit]("Create a stale file under the build target")
val assertStaleFileExists  = taskKey[Unit]("Assert the stale file exists under the active target")
val assertStaleFileCleaned = taskKey[Unit]("Assert the stale file was removed by clean")

def staleFile(base: File): File = base / "stale.txt"

seedStaleFile := {
  val file = staleFile(target.value)
  IO.createDirectory(file.getParentFile)
  IO.write(file, "stale")
}

assertStaleFileExists := {
  val file = staleFile(target.value)
  assert(file.exists, s"Expected stale file to exist at ${file.getAbsolutePath}")
}

assertStaleFileCleaned := {
  val file = staleFile(target.value)
  assert(!file.exists, s"Expected stale file to be removed from ${file.getAbsolutePath}")
}

// Skip push and publish in scripted tests.
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

// Target files created by scripted should not block the release pre-checks.
releaseIOIgnoreUntrackedFiles := true
