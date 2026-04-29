import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

// Regression: a `before-tag` per-project hook that installs late-bound
// monorepo version-file resolvers via `Extracted.appendWithSession` (legacy)
// must keep the resolvers visible to the next-version write phase.
// `tagReleasesPerProject.execute` calls `appendSessionSettings` to install
// the per-project tag; without the lift inside that step, the hook's overlay
// is dropped, and `set-next-versions` falls back to the build-default
// resolver and silently overwrites `version.sbt` instead of writing the
// late-bound file.
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
    name                                   := "hook-late-bound-settings-legacy-before-tag",
    releaseIOVcsIgnoreUntrackedFiles       := true,
    releaseIOMonorepoPolicyEnableRunTests  := false,
    releaseIOMonorepoPolicyEnablePublish   := false,
    releaseIOMonorepoPolicyEnablePush      := false,
    releaseIOMonorepoHooksBeforeTag        := Seq(
      MonorepoProjectHookIO.io("late-bound-settings-before-tag") { (ctx, _) =>
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
    checkLateBoundVersionFiles             := {
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
      // After release: set-release-versions wrote the release version into
      // `version.sbt` via the build-default resolver. The before-tag hook
      // installed the late-bound resolver, which the lift inside
      // `tagReleasesPerProject.execute` promoted to `session.rawAppend`.
      // The next-version write thus uses the late-bound resolver and lands
      // in `version.properties`.
      assertProject("core", "1.0.0", "1.1.0-SNAPSHOT")
      assertProject("api", "2.0.0", "2.1.0-SNAPSHOT")
    }
  )
