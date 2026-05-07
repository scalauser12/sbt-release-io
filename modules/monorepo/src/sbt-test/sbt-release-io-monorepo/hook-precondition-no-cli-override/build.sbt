import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

// Exercises the validate→execute boundary regression specifically: per-project
// versions are NOT pre-populated by CLI overrides on the test command. The
// validate-time tentative seed populates `project.versions` so that
// `afterVersionResolution` validate predicates can rely on them — but those
// values must be cleared at the boundary, otherwise:
//   1. `inquireVersions.execute` short-circuits on `resolvedVersions.isDefined`
//      and skips the per-project resolution that consumes interactive prompts /
//      re-evaluates the version task (e.g. re-reads
//      `releaseIOMonorepoVersioningReleaseVersion` after a hook mutates it).
//   2. `beforeVersionResolution` execute hooks see `Some(tentative)` instead of
//      the contract-mandated `None`.

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

// At beforeVersionResolution.execute, project.versions must be `None` even though
// validate seeded them tentatively.
def recordBeforeVersionResolutionExecute: MonorepoProjectHookIO =
  MonorepoProjectHookIO.sideEffect("record-before-version-resolution-execute") { (project, ctx) =>
    val base = Project.extract(ctx.state).get(baseDirectory)
    IO.blocking {
      val marker = base / "target" / s"before-version-resolution-execute-${project.name}.txt"
      sbt.IO.write(
        marker,
        s"release=${project.releaseVersion} next=${project.nextVersion}\n"
      )
    }
  }

// At inquireVersions.execute end (afterVersionResolution.execute), per-project
// versions must be the FRESH non-tentative resolution result — proving that
// `inquireVersions.execute` re-resolved instead of short-circuiting on the
// tentative seed.
def recordAfterVersionResolutionExecute: MonorepoProjectHookIO =
  MonorepoProjectHookIO.sideEffect("record-after-version-resolution-execute") { (project, ctx) =>
    val base = Project.extract(ctx.state).get(baseDirectory)
    IO.blocking {
      val marker = base / "target" / s"after-version-resolution-execute-${project.name}.txt"
      sbt.IO.write(
        marker,
        s"release=${project.releaseVersion} next=${project.nextVersion}\n"
      )
    }
  }

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                          := "hook-precondition-no-cli-override",
    releaseIOMonorepoPolicyEnablePublish          := false,
    releaseIOMonorepoPolicyEnablePush             := false,
    releaseIOVcsIgnoreUntrackedFiles              := true,
    releaseIOMonorepoHooksBeforeVersionResolution := Seq(recordBeforeVersionResolutionExecute),
    releaseIOMonorepoHooksAfterVersionResolution  := Seq(recordAfterVersionResolutionExecute)
  )
