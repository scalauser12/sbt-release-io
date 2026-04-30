import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

// Regression: a hook between set-release-version and commit-release-version that
// stages an unrelated file must not silently slip that file into the release
// commit. The release should fail before the commit so no tag is created.
name         := "commit-rejects-extra-staged"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnablePush        := false

releaseIOHooksAfterReleaseVersionWrite := Seq(
  ReleaseHookIO.sideEffect("stage-unrelated-file") { ctx =>
    _root_.cats.effect.IO.blocking {
      val base  = Project.extract(ctx.state).get(baseDirectory)
      val extra = base / "unrelated.txt"
      sbt.IO.write(extra, "extra\n")
      Process(Seq("git", "add", "unrelated.txt"), base).!!
      ()
    }
  }
)

val checkNoReleaseTag = taskKey[Unit]("Verify no v0.1.0 tag was created")
checkNoReleaseTag := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(!tags.contains("v0.1.0"), s"Did not expect v0.1.0 tag, got: $tags")
}

val checkInitialHeadOnly = taskKey[Unit]("Verify HEAD is still the initial commit")
checkInitialHeadOnly := {
  val commitCount = "git rev-list --count HEAD".!!.trim
  assert(
    commitCount == "1",
    s"Expected exactly one commit (initial), got: $commitCount"
  )
}
