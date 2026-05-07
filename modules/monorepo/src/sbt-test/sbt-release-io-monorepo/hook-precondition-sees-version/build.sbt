import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

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

def assertVersionsResolved(slot: String): MonorepoProjectHookIO =
  MonorepoProjectHookIO.precondition(s"assert-versions-visible-at-$slot") { (project, _) =>
    (project.releaseVersion, project.nextVersion) match {
      case (Some(_), Some(_)) => IO.unit
      case (release, next)    =>
        IO.raiseError(
          new IllegalStateException(
            s"$slot validate (${project.name}): expected Some(release)/Some(next) but saw " +
              s"release=$release, next=$next"
          )
        )
    }
  }

// Records project.releaseVersion / project.nextVersion at execute time so the test can assert
// the validate→execute boundary clearance: tentative seeds installed by validate must not
// bleed into the execute view of beforeVersionResolution hooks. CLI overrides pre-populate
// project.versions upfront and survive the boundary unchanged.
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

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                          := "hook-precondition-sees-version",
    releaseIOMonorepoPolicyEnablePublish          := false,
    releaseIOMonorepoPolicyEnablePush             := false,
    releaseIOVcsIgnoreUntrackedFiles              := true,
    releaseIOMonorepoHooksBeforeVersionResolution := Seq(recordBeforeVersionResolutionExecute),
    // Strong contract: post-version-resolution slots see project.versions at validate time,
    // even under `releaseIOMonorepo check`. CLI overrides pre-populate project.versions
    // before validate, so this also exercises the "explicit values short-circuit" path.
    releaseIOMonorepoHooksAfterVersionResolution  := Seq(
      assertVersionsResolved("after-version-resolution")
    ),
    releaseIOMonorepoHooksBeforeTag               := Seq(assertVersionsResolved("before-tag"))
  )
