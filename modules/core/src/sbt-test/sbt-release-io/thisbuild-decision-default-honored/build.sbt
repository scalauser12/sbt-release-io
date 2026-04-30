import scala.sys.process.*
import sbt.*
import sbt.Keys.*

// Regression: a ThisBuild-scoped user setting must not be shadowed by plugin-supplied
// project-scoped defaults. Before the fix, `pluginDefaultSettings` set
// `releaseIODefaultsPushAnswer := None` at project scope, which won over the user's
// `ThisBuild / releaseIODefaultsPushAnswer := Some(false)` and the release would still
// require an upstream. After the fix, the project-scope defaults are dropped so sbt's
// scope delegation surfaces the ThisBuild value.
ThisBuild / releaseIODefaultsPushAnswer      := Some(false)
ThisBuild / releaseIOVcsIgnoreUntrackedFiles := true

name         := "thisbuild-decision-default-honored"
scalaVersion := "2.12.18"

releaseIOPolicyEnableRunTests := false
releaseIOPolicyEnablePublish  := false

val checkReleaseTagged = taskKey[Unit]("Verify the release tag exists locally")
checkReleaseTagged := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(tags.contains("v0.1.0"), s"Expected v0.1.0 tag, got: $tags")
}
