import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

name         := "hook-installed-publish-skip"
scalaVersion := "2.12.18"

// Regression: a `beforePublish` hook that installs `publish/skip := true`
// must be observed by the publish task. Persistent overlays from
// set-release-version / commit-release-version / tag-release now live in
// `session.rawAppend` (Strategy B), so any structure-rebuilding step the
// publish path performs must not drop hook-installed settings.
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
  ReleaseHookIO.transform("hook-installed-publish-skip") { ctx =>
    _root_.cats.effect.IO.blocking {
      val extracted    = Project.extract(ctx.state)
      val updatedState = extracted.appendWithSession(
        Seq(publish / skip := true),
        ctx.state
      )
      ctx.withState(updatedState)
    }
  }
)

val checkPublishSkipped = taskKey[Unit]("Assert the publish action did NOT run")
checkPublishSkipped := {
  val marker = baseDirectory.value / "published.marker"
  assert(
    !marker.exists(),
    "publish action ran even though the before-publish hook set publish/skip := true"
  )
}
