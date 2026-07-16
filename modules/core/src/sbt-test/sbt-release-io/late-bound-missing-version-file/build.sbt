import scala.sys.process.*

import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO
import _root_.io.release.ReleaseSessionOps

name         := "late-bound-missing-version-file"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnablePush        := false

releaseIOHooksBeforeReleaseVersionWrite := Seq(
  ReleaseHookIO.transform("install-late-bound-missing-version-file") { ctx =>
    IO.blocking {
      val base        = Project.extract(ctx.state).get(baseDirectory)
      val missingFile = base / "version-typo.sbt"
      val updated     = ReleaseSessionOps.appendSessionSettings(
        ctx.state,
        Seq(releaseIOVersioningFile := missingFile)
      )
      ctx.withState(updated)
    }
  }
)

val checkRepositoryUnchanged = taskKey[Unit](
  "Assert a rejected late-bound version path leaves the repository unchanged"
)
checkRepositoryUnchanged := {
  val base            = baseDirectory.value
  val originalVersion = sbt.IO.read(base / "version.sbt").trim
  val commitCount     = Process("git rev-list --count HEAD", base).!!.trim
  val initialHead     = Process("git rev-parse refs/test/initial-head", base).!!.trim
  val currentHead     = Process("git rev-parse HEAD", base).!!.trim
  val tags            = Process("git tag", base).!!.trim

  assert(
    originalVersion == "version := \"0.1.0-SNAPSHOT\"",
    s"version.sbt should stay unchanged, got: $originalVersion"
  )
  assert(!(base / "version-typo.sbt").exists(), "version-typo.sbt must not be created")
  assert(commitCount == "1", s"HEAD should stay at the initial commit, count was $commitCount")
  assert(currentHead == initialHead, s"HEAD changed from $initialHead to $currentHead")
  assert(tags.isEmpty, s"no tag should be created, found: $tags")
}
