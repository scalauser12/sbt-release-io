import scala.sys.process.*
import sbt.*
import sbt.Keys.*

// Regression: monorepo push policy enabled but operator's effective push decision
// is "no" via `releaseIODefaultsPushAnswer := Some(false)` (shared decision key).
// Both the early `preparePushReleaseIfNeeded` warmup AND the later `pushChanges`
// step's validate/execute must short-circuit so a local/no-upstream monorepo
// release succeeds.
lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                          := "push-decision-no-skips-remote-warmup-monorepo",
    releaseIOVcsIgnoreUntrackedFiles              := true,
    releaseIOMonorepoPolicyEnableRunTests         := false,
    releaseIOMonorepoPolicyEnablePublish          := false,
    // Push policy stays ENABLED; only the decision-default declines.
    releaseIODefaultsPushAnswer                   := Some(false),
    releaseIOMonorepoDetectionEnabled             := false,
    checkReleaseTagged                            := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
      assert(tags.contains("core/v0.1.0"), s"Expected core/v0.1.0 tag, got: $tags")
    }
  )

val checkReleaseTagged = taskKey[Unit]("Verify the per-project release tag exists locally")
