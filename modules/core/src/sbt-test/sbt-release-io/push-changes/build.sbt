import scala.sys.process.*

name         := "push-changes-test"
scalaVersion := "2.12.18"

// Keep push-changes; filter out publish-artifacts, run-clean, run-tests
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "publish-artifacts" ||
  step.name == "run-clean" ||
  step.name == "run-tests"
}

releaseIOIgnoreUntrackedFiles := true

val checkPushed = taskKey[Unit]("Verify branch and tag were pushed to remote")
checkPushed := {
  val branch    = "git rev-parse --abbrev-ref HEAD".!!.trim
  val localHead = "git rev-parse HEAD".!!.trim

  // Check that the branch head on origin matches local HEAD
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

  // Check that the release tag exists on origin
  val originTags = "git ls-remote --tags origin".!!.trim
  assert(
    originTags.contains("refs/tags/v0.1.0"),
    s"Expected origin to contain tag v0.1.0 but tags were: $originTags"
  )
}
