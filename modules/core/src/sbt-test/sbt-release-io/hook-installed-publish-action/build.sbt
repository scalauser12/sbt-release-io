import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

name         := "hook-installed-publish-action"
scalaVersion := "2.12.18"

// Regression: a `beforePublish` hook that installs a custom
// `releaseIOPublishAction` via `appendWithSession` must be observed by
// publish-artifacts.execute. Strategy B moved persistent release overlays
// into `session.rawAppend`; any structure-rebuilding step downstream of the
// hook must not drop hook-installed settings.
publish / skip := false
publishTo      := Some(Resolver.file("local-test", baseDirectory.value / "publish-target"))

// Default publish action — should NOT run when the hook installs an override.
releaseIOPublishAction := {
  IO.write(baseDirectory.value / "default-published.marker", version.value + "\n")
}

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnablePush        := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false

releaseIOHooksBeforePublish := Seq(
  ReleaseHookIO.io("hook-installed-publish-action") { ctx =>
    _root_.cats.effect.IO.blocking {
      val extracted    = Project.extract(ctx.state)
      val base         = extracted.get(baseDirectory)
      val updatedState = extracted.appendWithSession(
        Seq(
          releaseIOPublishAction := {
            IO.write(base / "hook-published.marker", version.value + "\n")
          }
        ),
        ctx.state
      )
      ctx.withState(updatedState)
    }
  }
)

val checkHookActionRan = taskKey[Unit]("Assert the hook's publish action ran (not the default)")
checkHookActionRan := {
  val defaultMarker = baseDirectory.value / "default-published.marker"
  val hookMarker    = baseDirectory.value / "hook-published.marker"
  assert(
    !defaultMarker.exists(),
    "default publish action ran — hook-installed releaseIOPublishAction was dropped"
  )
  assert(
    hookMarker.exists(),
    "hook's publish action did not run — hook override was lost before publish-artifacts.execute"
  )
}
