import scala.sys.process.*

name := "untracked-version-file-test"

scalaVersion := "2.12.18"

releaseIOPolicyEnablePublish := false
releaseIOPolicyEnablePush    := false

// Without this the clean check would reject the untracked version.sbt outright.
releaseIOVcsIgnoreUntrackedFiles := true

val checkVersionFileTracked = taskKey[Unit](
  "Fail if version.sbt is still untracked after release."
)
checkVersionFileTracked := {
  val untracked = Process(Seq("git", "ls-files", "--other", "--exclude-standard")).!!.trim
  assert(
    !untracked.linesIterator.contains("version.sbt"),
    s"version.sbt is still untracked after release; output:\n$untracked"
  )
}

val checkVersionInTag = taskKey[Unit](
  "Fail if the release tag does not point at a commit containing the release version."
)
checkVersionInTag := {
  val tag = Process(Seq("git", "tag", "--list", "v0.1.0")).!!.trim
  assert(tag == "v0.1.0", s"Expected tag v0.1.0, found `$tag`")
  val tagged = Process(Seq("git", "show", "--name-only", "--format=", "v0.1.0")).!!.trim
  assert(
    tagged.linesIterator.contains("version.sbt"),
    s"Expected v0.1.0 to include a commit touching version.sbt; got:\n$tagged"
  )
  val tagContent = Process(Seq("git", "show", "v0.1.0:version.sbt")).!!.trim
  assert(
    tagContent.contains("0.1.0"),
    s"Expected version.sbt at tag v0.1.0 to contain release version; got:\n$tagContent"
  )
}
