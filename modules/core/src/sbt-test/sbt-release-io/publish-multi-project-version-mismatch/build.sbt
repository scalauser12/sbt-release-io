import scala.sys.process.*
import sbt.*
import sbt.Keys.*

lazy val child = (project in file("child"))
  .settings(
    name         := "mismatched-child",
    scalaVersion := "2.12.18",
    version      := "9.9.9",
    publishTo    := Some(Resolver.file("local", baseDirectory.value / "publish-target")),
    releaseIOPublishAction :=
      IO.touch(baseDirectory.value / "child-published.marker")
  )

lazy val root = (project in file("."))
  .aggregate(child)
  .settings(
    name         := "publish-multi-project-version-mismatch",
    scalaVersion := "2.12.18",
    publishTo    := Some(Resolver.file("local", baseDirectory.value / "publish-target")),
    releaseIOPublishAction :=
      IO.touch(baseDirectory.value / "root-published.marker"),

    releaseIOVersioningUseGlobal     := false,
    releaseIOVcsIgnoreUntrackedFiles := true,
    releaseIOPolicyEnablePush        := false,
    releaseIOPolicyEnableRunClean    := false,
    releaseIOPolicyEnableRunTests    := false
  )

val checkRejectedBeforeMutation = taskKey[Unit](
  "Assert the aggregate version mismatch was rejected before release side effects"
)
checkRejectedBeforeMutation := {
  val base = baseDirectory.value
  assert(!(base / "root-published.marker").exists(), "root publish action ran")
  assert(!(base / "child" / "child-published.marker").exists(), "child publish action ran")

  val commits = "git log --oneline".!!.trim.linesIterator.filter(_.nonEmpty).toList
  assert(commits.size == 1, s"expected one commit but found ${commits.size}: $commits")

  val tags = "git tag".!!.linesIterator.map(_.trim).filter(_.nonEmpty).toList
  assert(tags.isEmpty, s"expected no tags but found: ${tags.mkString(", ")}")

  val contents = IO.read(base / "version.sbt").trim
  assert(
    contents == "version := \"0.1.0-SNAPSHOT\"",
    s"version.sbt changed before validation rejected the mismatch: $contents"
  )
}
checkRejectedBeforeMutation / aggregate := false
