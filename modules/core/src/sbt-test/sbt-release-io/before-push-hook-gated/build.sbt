import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

// Regression: a `beforePush` hook must NOT run when `push-changes` is
// guaranteed to decline — i.e. when the operator declined via
// `releaseIODefaultsPushAnswer := Some(false)`. `enablePush` (the policy
// gate) keeps the step in the compiled pipeline; the runtime narrow gate
// is what suppresses the hook before the push step takes the
// `onDeclinePush` branch. Mirrors `after-push-hook-gated` for the
// pre-push lifecycle slot.
name         := "before-push-hook-gated"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnablePublish     := false
// Push policy stays ENABLED so the push step (and beforePush hooks) compile in.
// The decision-default declines, so the step takes the `onDeclinePush` branch.
releaseIODefaultsPushAnswer      := Some(false)

releaseIOHooksBeforePush := Seq(
  ReleaseHookIO.sideEffect("before-push-marker") { _ =>
    _root_.cats.effect.IO.blocking {
      sbt.IO.write(baseDirectory.value / "before-push.marker", "ran\n")
    }
  }
)

val checkBeforePushSuppressed = taskKey[Unit]("Assert the beforePush hook did NOT run")
checkBeforePushSuppressed := {
  val marker = baseDirectory.value / "before-push.marker"
  assert(
    !marker.exists(),
    "beforePush hook ran even though push was declined and no remote update happened"
  )
}
