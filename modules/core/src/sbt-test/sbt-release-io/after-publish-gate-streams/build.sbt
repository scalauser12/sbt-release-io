import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

// Regression: when a beforePublish hook installs `publish/skip := true` via
// session settings — making publish-artifacts a no-op — the afterPublish hook
// gate must observe the post-execute state and suppress its hooks. Previously
// the gate was frozen at validate time, so afterPublish hooks fired against a
// publish that never actually happened.
name         := "after-publish-gate-streams"
scalaVersion := "2.12.18"

publish / skip := false
publishTo      := Some(Resolver.file("local-test", baseDirectory.value / "publish-target"))

releaseIOPublishAction := {
  if ((publish / skip).value) ()
  else IO.write(baseDirectory.value / "published.marker", version.value + "\n")
}

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnablePush        := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false

releaseIOHooksBeforePublish := Seq(
  ReleaseHookIO.transform("install-skip") { ctx =>
    _root_.cats.effect.IO.blocking {
      val extracted    = Project.extract(ctx.state)
      val updatedState = extracted.appendWithSession(
        Seq(publish / skip := true),
        ctx.state
      )
      sbt.IO.write(baseDirectory.value / "before-publish.marker", "ran\n")
      ctx.withState(updatedState)
    }
  }
)

releaseIOHooksAfterPublish := Seq(
  ReleaseHookIO.sideEffect("after-publish-marker") { _ =>
    _root_.cats.effect.IO.blocking {
      sbt.IO.write(baseDirectory.value / "after-publish.marker", "ran\n")
    }
  }
)

val checkPublishSkipped = taskKey[Unit]("Assert the publish action did NOT run")
checkPublishSkipped := {
  val marker = baseDirectory.value / "published.marker"
  assert(!marker.exists(), "publish action ran despite the before-publish skip override")
}

val checkBeforePublishRan = taskKey[Unit]("Assert the before-publish hook ran")
checkBeforePublishRan := {
  val marker = baseDirectory.value / "before-publish.marker"
  assert(marker.exists(), "before-publish hook should have run before the gate streamed")
}

val checkAfterPublishSuppressed = taskKey[Unit]("Assert the after-publish hook did NOT run")
checkAfterPublishSuppressed := {
  val marker = baseDirectory.value / "after-publish.marker"
  assert(
    !marker.exists(),
    "after-publish hook ran even though publish was skipped at execute time"
  )
}
