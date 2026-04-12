import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

name := "push-race-before-push-test"

scalaVersion := "2.12.18"

releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnableRunClean    := false
releaseIOPolicyEnableRunTests    := false
releaseIOVcsIgnoreUntrackedFiles := true

def runCommand(command: Seq[String]): Unit = {
  val exitCode = Process(command).!
  assert(exitCode == 0, s"Command failed with exit code $exitCode: $command")
}

def advanceRemoteHook: ReleaseHookIO =
  ReleaseHookIO.action("advance-remote-before-push") { ctx =>
    _root_.cats.effect.IO.blocking {
      val base     = Project.extract(ctx.state).get(baseDirectory)
      val tempRepo = base / "temp-remote-advance"

      IO.delete(tempRepo)

      runCommand(Seq("git", "clone", "remotes/origin.git", tempRepo.getAbsolutePath))
      runCommand(
        Seq("git", "-C", tempRepo.getAbsolutePath, "config", "user.email", "other@example.com")
      )
      runCommand(Seq("git", "-C", tempRepo.getAbsolutePath, "config", "user.name", "Other"))
      runCommand(
        Seq(
          "git",
          "-C",
          tempRepo.getAbsolutePath,
          "commit",
          "--allow-empty",
          "-m",
          "Remote advance before push"
        )
      )
      runCommand(Seq("git", "-C", tempRepo.getAbsolutePath, "push", "origin", "main"))
    }
  }

releaseIOHooksBeforePush := Seq(advanceRemoteHook)

val checkRemoteUnchanged =
  taskKey[Unit]("Verify origin did not receive the release commit or release tag")

checkRemoteUnchanged := {
  val localHead        = "git rev-parse HEAD".!!.trim
  val originHeadOutput =
    "git ls-remote --heads origin refs/heads/main".!!.trim
  assert(originHeadOutput.nonEmpty, "Expected origin/main to exist")

  val originHead = originHeadOutput.linesIterator.next().split("\\s+")(0)
  assert(
    originHead != localHead,
    s"Expected origin/main to stay behind local HEAD $localHead but found $originHead"
  )

  val originTags = "git ls-remote --tags origin".!!.trim
  assert(
    !originTags.contains("refs/tags/v0.1.0"),
    s"Did not expect origin to contain v0.1.0 but tags were: $originTags"
  )

  val versionContents = IO.read(baseDirectory.value / "version.sbt")
  assert(
    versionContents.contains("0.2.0-SNAPSHOT"),
    s"Expected local version to advance before push failure but got: $versionContents"
  )
}
