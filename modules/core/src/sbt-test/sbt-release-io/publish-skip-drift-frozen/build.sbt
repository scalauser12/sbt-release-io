import _root_.io.release.ReleaseHookIO
import sbt.*
import sbt.Keys.*

name         := "publish-skip-drift-frozen"
scalaVersion := "2.12.18"

publish / skip := true

releaseIOPublishAction :=
  IO.touch(baseDirectory.value / "published.marker")

releaseIOHooksAfterTag := Seq(
  ReleaseHookIO.transform("enable-publish-after-validation") { ctx =>
    _root_.cats.effect.IO.blocking {
      IO.touch(baseDirectory.value / "after-tag.marker")
      ctx.withState(
        Project
          .extract(ctx.state)
          .appendWithSession(Seq(publish / skip := false), ctx.state)
      )
    }
  }
)

releaseIOHooksBeforePublish := Seq(
  ReleaseHookIO.sideEffect("unexpected-before-publish") { _ =>
    _root_.cats.effect.IO.blocking(IO.touch(baseDirectory.value / "before-publish.marker"))
  }
)

releaseIOHooksAfterPublish := Seq(
  ReleaseHookIO.sideEffect("unexpected-after-publish") { _ =>
    _root_.cats.effect.IO.blocking(IO.touch(baseDirectory.value / "after-publish.marker"))
  }
)

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnablePush        := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false

val checkFrozenPublishDecision = taskKey[Unit](
  "Assert a target skipped during checked validation was not enabled later"
)
checkFrozenPublishDecision := {
  val base = baseDirectory.value
  assert((base / "after-tag.marker").exists(), "after-tag hook did not run")
  Seq(
    "published.marker",
    "before-publish.marker",
    "after-publish.marker"
  ).foreach { name =>
    assert(!(base / name).exists(), s"unexpected marker exists: $name")
  }
}
