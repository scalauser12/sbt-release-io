import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

// Regression: a `before-version-resolution` hook that installs a late-bound
// `releaseIOVersioningFile` pointing outside the VCS root must be caught at
// `set-release-version` execute time — before any mutation of the external file.
name         := "late-bound-version-file-outside-vcs"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnablePush        := false

val createExternalFile = taskKey[Unit]("Create the external version file before the test runs")
createExternalFile := {
  val external = baseDirectory.value.getParentFile / "external-version.sbt"
  sbt.IO.write(external, "version := \"0.1.0-SNAPSHOT\"\n")
}

releaseIOHooksBeforeVersionResolution := Seq(
  ReleaseHookIO.transform("install-late-bound-external-version-file") { ctx =>
    IO.blocking {
      val base         = Project.extract(ctx.state).get(baseDirectory)
      val external     = base.getParentFile / "external-version.sbt"
      val updatedState = Project.extract(ctx.state).appendWithSession(
        Seq(releaseIOVersioningFile := external),
        ctx.state
      )
      ctx.withState(updatedState)
    }
  }
)

val checkExternalFileUntouched = taskKey[Unit]("Assert the external file was not modified")
checkExternalFileUntouched := {
  val external = baseDirectory.value.getParentFile / "external-version.sbt"
  val contents = sbt.IO.read(external)
  assert(
    contents.contains("0.1.0-SNAPSHOT"),
    s"external file should not have been mutated, got: $contents"
  )
}
