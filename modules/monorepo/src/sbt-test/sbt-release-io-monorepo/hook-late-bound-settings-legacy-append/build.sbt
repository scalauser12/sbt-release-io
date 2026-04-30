import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

// Regression: a `before-version-resolution` hook that uses the legacy
// `Extracted.appendWithSession` to install late-bound monorepo version-file
// resolvers must keep those resolvers visible across every project's write.
// `writeProjectVersion`'s trailing `appendSessionSettings` rebuilds the
// structure from `session.mergeSettings` (which excludes `appendWithSession`
// overlays); without the lift, only the FIRST selected project's write sees
// the hook resolver — every later project (and the next-version phase)
// would silently fall back to the build default and write to `version.sbt`.
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
  taskKey[Unit]("Check both projects' late-bound version files")

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
    name                                          := "hook-late-bound-settings-legacy-append",
    releaseIOVcsIgnoreUntrackedFiles              := true,
    releaseIOMonorepoPolicyEnableRunTests         := false,
    releaseIOMonorepoPolicyEnablePublish          := false,
    releaseIOMonorepoPolicyEnablePush             := false,
    releaseIOMonorepoHooksBeforeVersionResolution := Seq(
      MonorepoProjectHookIO.transform("late-bound-version-settings-legacy") { (_, ctx) =>
        IO.blocking {
          // Legacy install path: `Extracted.appendWithSession` writes only
          // into `structure.settings`. The plugin's lift inside
          // `writeProjectVersion` promotes these into `session.rawAppend` so
          // they survive subsequent structure rebuilds.
          val extracted    = Project.extract(ctx.state)
          val updatedState = extracted.appendWithSession(
            lateBoundVersionSettings,
            ctx.state
          )
          val baseDir      = Project.extract(updatedState).get(baseDirectory)
          sbt.IO.touch(baseDir / "late-bound-version-settings-ran")
          ctx.withState(updatedState)
        }
      }
    ),
    checkLateBoundVersionFiles                    := {
      def assertProject(name: String, expectedRuntime: String): Unit = {
        val runtimeVersion = sbt.IO.read(file(s"$name/version.properties")).trim
        val scopedVersion  = sbt.IO.read(file(s"$name/version.sbt")).trim
        assert(
          runtimeVersion == expectedRuntime,
          s"Unexpected $name/version.properties: '$runtimeVersion' (expected '$expectedRuntime')"
        )
        assert(
          scopedVersion.contains("""version := "0.1.0-SNAPSHOT""""),
          s"$name/version.sbt should stay unchanged, but was: $scopedVersion"
        )
      }
      // After release: both projects' next versions land in
      // `version.properties`; both `version.sbt` files stay at the original
      // snapshot. Pre-fix, api/version.sbt would be overwritten with the
      // release version because the late-bound resolver was dropped after
      // core's write.
      assertProject("core", "1.1.0-SNAPSHOT")
      assertProject("api", "2.1.0-SNAPSHOT")
    }
  )
