import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO
import _root_.io.release.ReleaseSessionOps

name         := "tag-preflight-skipped-with-hook"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnablePush        := false

// A `beforeTag` hook rewrites the tag name from the default (`v0.1.0`) to
// `release-0.1.0`. If `tag-preflight` ran here, it would evaluate the default
// `v0.1.0` and abort because that tag already exists in the repo (set up by the
// scripted test below) — even though `release-0.1.0` is free and the release
// would have completed successfully. The lifecycle auto-disables `tag-preflight`
// whenever any of `beforeReleaseVersionWrite`, `afterReleaseVersionWrite`,
// `beforeReleaseCommit`, `afterReleaseCommit`, or `beforeTag` hooks are
// configured, so this release proceeds.
releaseIOHooksBeforeTag := Seq(
  ReleaseHookIO.transform("rewrite-tag-name") { ctx =>
    _root_.cats.effect.IO.blocking {
      val updatedState = ReleaseSessionOps.appendSessionSettings(
        ctx.state,
        Seq(releaseIOVcsTagName := "release-0.1.0")
      )
      ctx.withState(updatedState)
    }
  }
)

val checkRewrittenTagPresent  = taskKey[Unit]("Assert the rewritten tag was created")
val checkDefaultTagUnchanged  =
  taskKey[Unit]("Assert the pre-existing default tag was not touched")

checkRewrittenTagPresent := {
  val tags = "git tag".!!.trim.split("\n").map(_.trim).filter(_.nonEmpty).toSet
  assert(
    tags.contains("release-0.1.0"),
    s"Expected 'release-0.1.0' tag (installed by beforeTag hook). Tags: ${tags.mkString(", ")}"
  )
}

checkDefaultTagUnchanged := {
  // `v0.1.0` was created in the scripted setup pointing at the initial commit.
  // After release the lifecycle has created the release commit and the next-
  // version commit, so the initial commit is now HEAD~2. `v0.1.0` must still
  // point there (proving the preflight didn't mistakenly overwrite or rewrite
  // it).
  val v010Sha    = ("git rev-list -n 1 v0.1.0".!!).trim
  val initialSha = ("git rev-parse HEAD~2".!!).trim
  assert(
    v010Sha == initialSha,
    s"Expected v0.1.0 to still point at the initial commit ($initialSha) but found $v010Sha"
  )
}
