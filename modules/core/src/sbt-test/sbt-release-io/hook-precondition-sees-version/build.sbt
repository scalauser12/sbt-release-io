import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

name := "hook-precondition-sees-version"

scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnablePush        := false
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnableRunClean    := false

publishTo := Some(Resolver.file("test-repo", baseDirectory.value / "target" / "test-repo"))

// Lock the contract boundary: at beforeVersionResolution, versions are NOT yet resolved
// (tentative seed lives on inquire-versions.validate which runs after this phase). The
// `precondition` covers the validate pass; the trailing `sideEffect` covers the execute
// pass — validate-time tentative seeds must be cleared at the validate→execute boundary
// so beforeVersionResolution execute hooks observe `None`, matching their validate view.
releaseIOHooksBeforeVersionResolution := Seq(
  ReleaseHookIO.precondition("assert-no-version-before-resolution") { ctx =>
    if (ctx.releaseVersion.isEmpty && ctx.nextVersion.isEmpty) IO.unit
    else
      IO.raiseError(
        new IllegalStateException(
          s"beforeVersionResolution: expected versions to be empty but saw " +
            s"release=${ctx.releaseVersion}, next=${ctx.nextVersion}"
        )
      )
  },
  ReleaseHookIO.sideEffect("record-before-version-resolution-execute") { ctx =>
    val base = Project.extract(ctx.state).get(baseDirectory)
    IO.blocking {
      val marker = base / "target" / "before-version-resolution-execute.txt"
      sbt.IO.write(
        marker,
        s"release=${ctx.releaseVersion},next=${ctx.nextVersion}\n"
      )
    }
  }
)

// The strong contract: at every post-resolution slot, versions MUST be visible at validate
// time so `releaseIO check` predicates can rely on them.
def assertVersionsResolved(slot: String): ReleaseHookIO =
  ReleaseHookIO.precondition(s"assert-versions-visible-at-$slot") { ctx =>
    (ctx.releaseVersion, ctx.nextVersion) match {
      case (Some(_), Some(_)) => IO.unit
      case (release, next)    =>
        IO.raiseError(
          new IllegalStateException(
            s"$slot validate: expected Some(release)/Some(next) but saw " +
              s"release=$release, next=$next"
          )
        )
    }
  }

releaseIOHooksAfterVersionResolution := Seq(assertVersionsResolved("after-version-resolution"))
releaseIOHooksBeforeTag              := Seq(assertVersionsResolved("before-tag"))
releaseIOHooksBeforePublish          := Seq(assertVersionsResolved("before-publish"))
