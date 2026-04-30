import sbt.*
import sbt.Keys.*

// Regression: a misconfigured `releaseIOVersioningFile` pointing outside the
// VCS root must fail at validate time (`releaseIO check`) before any execute-time
// mutation of the external file.
name         := "version-file-outside-vcs"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnablePush        := false

// Point at a sibling-of-the-repo path. The temp dir's parent is outside the
// scripted repo's git root.
releaseIOVersioningFile := baseDirectory.value.getParentFile / "external-version.sbt"

val createExternalFile = taskKey[Unit]("Create the external version file before the test runs")
createExternalFile := {
  val external = baseDirectory.value.getParentFile / "external-version.sbt"
  IO.write(external, "version := \"0.1.0-SNAPSHOT\"\n")
}

val checkExternalFileUntouched = taskKey[Unit]("Assert the external file was not modified")
checkExternalFileUntouched := {
  val external = baseDirectory.value.getParentFile / "external-version.sbt"
  val contents = IO.read(external)
  assert(
    contents.contains("0.1.0-SNAPSHOT"),
    s"external file should not have been mutated, got: $contents"
  )
}
