import scala.sys.process.*
import sbt.IO

name         := "version-task-sees-snapshot"
scalaVersion := "2.12.18"

// Regression: validate-time overlay used to leak into execute via runMainSegment's
// `validatedCtx → runActionPhase`. With `release-version 0.1.0`, the next-version
// task ran with `version.value = "0.1.0"` instead of the snapshot, computing the
// wrong next. The local-overlay refactor confines the overlay to publishArtifacts.validate
// and the publish-hook gate; inquireVersions.execute sees the original snapshot state.
//
// This test captures `version.value` from the next-version task and asserts it is
// the snapshot, proving the overlay no longer leaks.
releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnablePush        := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false

// Configure publishTo so the publish step doesn't fail on missing target.
publishTo := Some(Resolver.file("local", baseDirectory.value / "publish-target"))

// Don't trigger any version-dependent skip — keep it simple.
publish / skip := true

releaseIOVersioningNextVersion := { (current: String) =>
  // Capture what `version.value` looks like at task evaluation time. With the
  // bug, this would be "0.1.0" (the release override). With the fix, it should
  // be "0.1.0-SNAPSHOT" (the snapshot from version.sbt).
  val captured = version.value
  IO.write(file("captured-next-version-input.txt"), s"$captured\n")
  // Compute next version normally.
  current.stripSuffix("-SNAPSHOT").split('.') match {
    case Array(major, minor, _*) => s"$major.${minor.toInt + 1}.0-SNAPSHOT"
    case _                       => "0.2.0-SNAPSHOT"
  }
}

val checkCapturedVersionIsSnapshot = taskKey[Unit](
  "Assert the next-version task saw the snapshot version, not the release override"
)
checkCapturedVersionIsSnapshot := {
  val captured = IO.read(file("captured-next-version-input.txt")).trim
  assert(
    captured == "0.1.0-SNAPSHOT",
    s"Expected next-version task to see snapshot 'version.value' but got: '$captured'. " +
      "This indicates the validate-time release-version overlay leaked into execute."
  )
}
