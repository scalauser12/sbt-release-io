import scala.sys.process.*
import sbt.IO

// Regression: with `publish / skip := isSnapshot.value` and a configured
// publishTo, the per-project release-version session overlay applied by
// set-release-version.execute can be dropped by intermediate steps
// (commit-release-versions, tag-releases) that call appendWithSession with
// their own metadata. Without the fix, publish-artifacts.execute evaluates
// `publish/skip` against the original snapshot state and the publish task
// no-ops silently — even though the frozen `beforePublish`/`afterPublish`
// hooks were captured against the post-release-version state.
lazy val core = (project in file("core"))
  .settings(
    name           := "core",
    scalaVersion   := "2.12.18",
    publish / skip := isSnapshot.value,
    publishTo      := Some(
      Resolver.file("local-test", baseDirectory.value.getParentFile / "publish-target")
    ),
    // Custom publish action writes a marker file when it actually runs.
    releaseIOPublishAction := {
      IO.write(
        baseDirectory.value.getParentFile / "core-published.marker",
        version.value + "\n"
      )
    }
  )

val checkPublishRan = taskKey[Unit]("Assert the publish action wrote its marker")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "publish-runs-after-tag-test",
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,
    checkPublishRan                       := {
      val marker = baseDirectory.value / "core-published.marker"
      assert(
        marker.exists(),
        "core's publish action did not run — overlay may have been lost between " +
          "set-release-version.execute and publish-artifacts.execute"
      )
      val recorded = IO.read(marker).trim
      assert(
        recorded == "1.0.0",
        s"publish ran but with unexpected version.value: '$recorded' (expected 1.0.0)"
      )
    }
  )
