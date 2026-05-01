import sbt.IO

// Pin a version file whose name starts with `-`. Without the `--` separator on
// `git add`, the leading dash would be interpreted as a CLI flag and `git add
// -version.sbt` would fail after `set-release-version` had already mutated the
// file, leaving the working tree dirty. This test pins the defence-in-depth
// fix end-to-end: a release with a dash-prefixed version file completes and
// the next-version commit lands cleanly.
name                            := "dash-prefixed-version-file-test"
scalaVersion                    := "2.12.18"

releaseIOVersioningFile         := baseDirectory.value / "-version.sbt"
releaseIOVersioningUseGlobal    := false
releaseIOPolicyEnablePublish    := false
releaseIOPolicyEnablePush       := false

val checkDashVersionFile =
  inputKey[Unit]("Check that -version.sbt contains the expected version string")
checkDashVersionFile := {
  import sbt.complete.DefaultParsers.*
  val expected = spaceDelimited("<version>").parsed.mkString(" ")
  val contents = IO.read(file("-version.sbt"))
  assert(
    contents.contains(expected),
    s"Expected -version.sbt to contain '$expected' but got:\n$contents"
  )
}
