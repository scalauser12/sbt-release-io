name := "late-bound-version-settings"

scalaVersion := "2.12.18"

releaseIOIgnoreUntrackedFiles := true

enablePlugins(LateBoundVersionPlugin)

releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts" || step.name == "run-tests"
}

val checkRuntimeVersionFile = taskKey[Unit]("Check the late-bound version file")

checkRuntimeVersionFile := {
  val runtimeVersion = IO.read(file("version.properties")).trim
  val rootVersion    = IO.read(file("version.sbt")).trim

  assert(runtimeVersion == "0.2.0-SNAPSHOT", s"Unexpected version.properties: $runtimeVersion")
  assert(
    rootVersion.contains("""version := "0.1.0-SNAPSHOT""""),
    s"version.sbt should stay unchanged, but was: $rootVersion"
  )
}
