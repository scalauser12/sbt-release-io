lazy val lib = (project in file("lib"))
  .settings(
    name                            := "lib",
    scalaVersion                    := "2.12.18",
    // Custom publish action: write a marker file
    releaseIOPublishAction := {
      val marker = baseDirectory.value / "marker" / "publish.log"
      IO.createDirectory(marker.getParentFile)
      IO.append(marker, "lib-published\n")
    }
  )

lazy val root = (project in file("."))
  .aggregate(lib)
  .settings(
    name           := "publish-skip-root-test",
    scalaVersion   := "2.12.18",
    publish / skip := true,

    releaseIOPublishChecks := false,

    releaseIOVcsIgnoreUntrackedFiles := true,
    releaseIOPolicyEnablePush           := false,
    releaseIOPolicyEnableRunClean       := false,
    releaseIOPolicyEnableRunTests       := false
  )

val checkPublished = taskKey[Unit]("Verify child publish ran despite root skip")
checkPublished := {
  val marker = file("lib") / "marker" / "publish.log"
  assert(marker.exists, s"Marker file not found — child publish did not run")
  val lines  = IO.readLines(marker).filter(_.nonEmpty)
  assert(
    lines == List("lib-published"),
    s"Expected exactly one 'lib-published' entry but got: ${lines.mkString(", ")}"
  )
}
