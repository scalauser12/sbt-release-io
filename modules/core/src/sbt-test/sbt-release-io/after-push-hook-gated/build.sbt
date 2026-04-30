import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

// Regression: an `afterPush` hook must NOT run when `push-changes` succeeded
// without actually pushing — i.e. when the operator declined via
// `releaseIODefaultsPushAnswer := Some(false)`, non-interactive no-default,
// interactive decline, or EOF. `enablePush` (the policy gate) keeps the step
// in the compiled pipeline; the runtime narrow gate is what suppresses the
// hook when the push step took the `onDeclinePush` branch.
name         := "after-push-hook-gated"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnablePublish     := false
// Push policy stays ENABLED so the push step (and afterPush hooks) compile in.
// The decision-default declines, so the step takes the `onDeclinePush` branch.
releaseIODefaultsPushAnswer      := Some(false)

releaseIOHooksAfterPush := Seq(
  ReleaseHookIO.sideEffect("after-push-marker") { _ =>
    _root_.cats.effect.IO.blocking {
      sbt.IO.write(baseDirectory.value / "after-push.marker", "ran\n")
    }
  }
)

val checkAfterPushSuppressed = taskKey[Unit]("Assert the afterPush hook did NOT run")
checkAfterPushSuppressed := {
  val marker = baseDirectory.value / "after-push.marker"
  assert(
    !marker.exists(),
    "afterPush hook ran even though push was declined and no remote update happened"
  )
}
