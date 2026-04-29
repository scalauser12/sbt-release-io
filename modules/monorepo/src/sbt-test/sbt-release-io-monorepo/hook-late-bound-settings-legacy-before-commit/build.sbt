import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO

// Regression: a `before-release-commit` global hook that installs late-bound
// monorepo version-file resolvers via `Extracted.appendWithSession` (legacy)
// must keep the resolvers visible to the next-version write phase.
// `commitVersions` calls `appendSessionSettings` to install the release hash;
// without the lift inside `commitVersions`, the hook's overlay is dropped
// here, and `set-next-versions` falls back to the build-default resolver and
// silently overwrites `version.sbt` instead of writing to the late-bound file.
lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

val checkLateBoundVersionFiles =
  taskKey[Unit]("Check late-bound resolver was honored across release/next phases")

def lateBoundVersionSettings: Seq[Setting[?]] =
  Seq(
    releaseIOMonorepoVersioningFile         := { (ref: ProjectRef, state: State) =>
      Project.extract(state).get(ref / baseDirectory) / "version.properties"
    },
    releaseIOMonorepoVersioningReadVersion  := { file =>
      IO.blocking(sbt.IO.read(file).trim)
    },
    releaseIOMonorepoVersioningFileContents := { (_, version) =>
      IO.pure(version + "\n")
    }
  )

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                    := "hook-late-bound-settings-legacy-before-commit",
    releaseIOVcsIgnoreUntrackedFiles        := true,
    releaseIOMonorepoPolicyEnableRunTests   := false,
    releaseIOMonorepoPolicyEnablePublish    := false,
    releaseIOMonorepoPolicyEnablePush       := false,
    releaseIOMonorepoHooksBeforeReleaseCommit := Seq(
      MonorepoGlobalHookIO.io("late-bound-settings-before-commit") { ctx =>
        IO.blocking {
          val extracted    = Project.extract(ctx.state)
          val updatedState = extracted.appendWithSession(
            lateBoundVersionSettings,
            ctx.state
          )
          val baseDir      = Project.extract(updatedState).get(baseDirectory)
          sbt.IO.touch(baseDir / "late-bound-settings-ran")
          ctx.withState(updatedState)
        }
      }
    ),
    checkLateBoundVersionFiles              := {
      def assertProject(name: String, releaseV: String, nextV: String): Unit = {
        val sbtFile        = file(s"$name/version.sbt")
        val propertiesFile = file(s"$name/version.properties")
        val scopedVersion  = sbt.IO.read(sbtFile).trim
        val runtimeVersion = sbt.IO.read(propertiesFile).trim
        assert(
          scopedVersion.contains(s"""version := "$releaseV""""),
          s"$name/version.sbt should contain release '$releaseV', was: $scopedVersion"
        )
        assert(
          runtimeVersion == nextV,
          s"$name/version.properties should contain next '$nextV', was: '$runtimeVersion'"
        )
      }
      // After release: set-release-versions used the build-default
      // resolver (hook hadn't run yet), so the release version is in
      // `version.sbt`. The hook then installed the late-bound resolver, which
      // the lift inside `commitVersions` promoted to `session.rawAppend`. The
      // next-version write thus uses the late-bound resolver and lands in
      // `version.properties`. Pre-fix, the hook overlay would be dropped at
      // `commitVersions` and the next-version write would clobber `version.sbt`.
      assertProject("core", "1.0.0", "1.1.0-SNAPSHOT")
      assertProject("api", "2.0.0", "2.1.0-SNAPSHOT")
    }
  )
