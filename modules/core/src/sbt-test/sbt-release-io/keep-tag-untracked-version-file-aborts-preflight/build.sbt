import scala.sys.process.*

// The version file already contains the requested release bytes, but it is untracked.
// The release commit will therefore add the file even though the write itself is a no-op.
// Keeping a tag at the current HEAD must be rejected by tag preflight before that commit.
name         := "keep-tag-untracked-version-file-aborts-preflight-test"
scalaVersion := "2.12.18"

releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnablePush        := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false
releaseIOVcsIgnoreUntrackedFiles := true

val checkPreflightAborted = taskKey[Unit](
  "Verify keep-tag preflight aborted before staging or committing the untracked version file."
)

checkPreflightAborted := {
  val base = baseDirectory.value

  val commits = Process(Seq("git", "rev-list", "--count", "HEAD"), base).!!.trim
  assert(commits == "1", s"Expected only the initial commit, found $commits commits")

  val tracked = Process(Seq("git", "ls-files", "--", "version.sbt"), base).!!.trim
  assert(tracked.isEmpty, s"Expected version.sbt to remain untracked, got: $tracked")

  val untracked = Process(
    Seq("git", "ls-files", "--others", "--exclude-standard", "--", "version.sbt"),
    base
  ).!!.trim
  assert(untracked == "version.sbt", s"Expected version.sbt to remain untracked, got: $untracked")

  val staged =
    Process(Seq("git", "diff", "--cached", "--name-only", "--", "version.sbt"), base).!!.trim
  assert(staged.isEmpty, s"Expected version.sbt not to be staged, got: $staged")

  val head      = Process(Seq("git", "rev-parse", "HEAD"), base).!!.trim
  val tagCommit = Process(Seq("git", "rev-parse", "v0.1.0^{commit}"), base).!!.trim
  assert(tagCommit == head, s"Expected v0.1.0 to remain at $head, got $tagCommit")

  val versionContents = IO.read(base / "version.sbt")
  assert(
    versionContents.contains("0.1.0") && !versionContents.contains("0.2.0-SNAPSHOT"),
    s"Expected version.sbt to remain at 0.1.0 but got: $versionContents"
  )
}
