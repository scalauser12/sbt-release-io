import scala.sys.process.*

name         := "push-explicit-tag-test"
scalaVersion := "2.12.18"

// Keep push-changes; filter out publish-artifacts, run-clean, run-tests
releaseIOPolicyEnablePublish  := false
releaseIOPolicyEnableRunClean := false
releaseIOPolicyEnableRunTests := false

releaseIOVcsIgnoreUntrackedFiles := true

val checkPushedTagsOnlyInclude = taskKey[Unit](
  "Verify the release tag was pushed and unrelated reachable annotated tags were not"
)
checkPushedTagsOnlyInclude := {
  val branch    = "git rev-parse --abbrev-ref HEAD".!!.trim
  val localHead = "git rev-parse HEAD".!!.trim

  // Branch head on origin matches local HEAD
  val originBranchOutput = s"git ls-remote --heads origin refs/heads/$branch".!!.trim
  assert(
    originBranchOutput.nonEmpty,
    s"Expected origin to have branch $branch but it was not found"
  )
  val originHead         = originBranchOutput.linesIterator.next().split("\\s+")(0)
  assert(
    originHead == localHead,
    s"Expected origin/$branch to point to $localHead but found $originHead"
  )

  // Release tag is on origin
  val originTags = "git ls-remote --tags origin".!!.trim
  assert(
    originTags.contains("refs/tags/v0.1.0"),
    s"Expected origin to contain tag v0.1.0 but tags were: $originTags"
  )

  // Unrelated annotated tag reachable from the pushed commit is NOT on origin.
  // The old `--follow-tags` implementation would ship this tag; the explicit
  // per-tag push must not.
  assert(
    !originTags.contains("refs/tags/legacy-v0.9"),
    s"Expected origin to NOT contain unrelated tag legacy-v0.9 but tags were: $originTags"
  )
}
