import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

// Exercises the validate→execute boundary regression specifically for the
// core single-project flow with a PARTIAL CLI override: only `release-version
// 1.2.3` on the CLI, no `next-version`. The validate-time tentative seed
// populates `ctx.versions` (release from the CLI override, next from the
// non-prompting default task), and the boundary clearance must drop both so
// `beforeVersionResolution` execute hooks observe `None` while the
// `executionState.plan.releaseVersionOverride` still flows through to the
// execute-time resolver and produces the same release version. Mirrors the
// monorepo `hook-precondition-no-cli-override` fixture.

name := "hook-precondition-partial-cli-override"

scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnablePush        := false
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnableRunClean    := false

publishTo := Some(Resolver.file("test-repo", baseDirectory.value / "target" / "test-repo"))

// At beforeVersionResolution.execute, ctx.versions must be `None` even though
// validate seeded them tentatively from the partial CLI override.
releaseIOHooksBeforeVersionResolution := Seq(
  ReleaseHookIO.sideEffect("record-before-version-resolution-execute") { ctx =>
    val base = Project.extract(ctx.state).get(baseDirectory)
    IO.blocking {
      val marker = base / "target" / "before-version-resolution-execute.txt"
      sbt.IO.write(
        marker,
        s"release=${ctx.releaseVersion} next=${ctx.nextVersion}\n"
      )
    }
  }
)

// At afterVersionResolution.execute, the CLI override must have flowed through
// the second resolution pass and produced the same release version (proving the
// boundary clearance does not lose the override that lives on
// `executionState.plan.releaseVersionOverride`).
releaseIOHooksAfterVersionResolution := Seq(
  ReleaseHookIO.sideEffect("record-after-version-resolution-execute") { ctx =>
    val base = Project.extract(ctx.state).get(baseDirectory)
    IO.blocking {
      val marker = base / "target" / "after-version-resolution-execute.txt"
      sbt.IO.write(
        marker,
        s"release=${ctx.releaseVersion} next=${ctx.nextVersion}\n"
      )
    }
  }
)
