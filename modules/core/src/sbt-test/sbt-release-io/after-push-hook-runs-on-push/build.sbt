import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

// Positive companion to `after-push-hook-gated`: when `push-changes` actually
// pushes to a tracking remote, the afterPush hook MUST run. This locks in the
// `markPushExecuted` wiring on the real push paths so future refactors can't
// silently strand afterPush hooks.
name         := "after-push-hook-runs-on-push"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnablePublish     := false

releaseIOHooksAfterPush := Seq(
  ReleaseHookIO.sideEffect("after-push-marker") { _ =>
    _root_.cats.effect.IO.blocking {
      sbt.IO.write(baseDirectory.value / "after-push.marker", "ran\n")
    }
  }
)

val checkAfterPushRan = taskKey[Unit]("Assert the afterPush hook ran")
checkAfterPushRan := {
  val marker = baseDirectory.value / "after-push.marker"
  assert(marker.exists(), "afterPush hook should have run after a successful push")
}
