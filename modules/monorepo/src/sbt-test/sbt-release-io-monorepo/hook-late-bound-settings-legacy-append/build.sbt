import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.ReleaseSessionOps
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
    name               := "core",
    scalaVersion       := "2.12.18",
    crossScalaVersions := Seq("2.12.18", "2.13.16"),
    publish / skip     := true
  )

lazy val api = (project in file("api"))
  .settings(
    name               := "api",
    scalaVersion       := "2.12.18",
    crossScalaVersions := Seq("2.12.18", "2.13.16"),
    publish / skip     := true
  )

val checkLateBoundVersionFiles =
  taskKey[Unit]("Check both projects' late-bound version files")

val lateBoundVersionFileParts =
  settingKey[Seq[String]]("Late-bound version file name parts used by the resolver")

def lateBoundVersionSettings: Seq[Setting[?]] =
  Seq(
    ThisBuild / lateBoundVersionFileParts   := Seq("version"),
    lateBoundVersionFileParts += "properties",
    releaseIOMonorepoVersioningFile         := {
      // The project-scoped `+=` is non-definitive and inherits its base from
      // ThisBuild. Promotion must retain both definitions so the resolver
      // remains valid after the next persistent session rebuild.
      val fileName = lateBoundVersionFileParts.value.mkString(".")
      (ref: ProjectRef, state: State) => Project.extract(state).get(ref / baseDirectory) / fileName
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
    name                                           := "hook-late-bound-settings-legacy-append",
    releaseIOVcsIgnoreUntrackedFiles               := true,
    releaseIOMonorepoPolicyEnableRunTests          := false,
    // Keep publishing enabled so `cross` performs a real switch/restore cycle
    // between the release-version and next-version writes. Per-project skip
    // prevents artifact publication while still exercising the bridge marker.
    releaseIOMonorepoPolicyEnablePublish           := true,
    releaseIOMonorepoPolicyEnablePush              := false,
    releaseIOMonorepoHooksBeforeVersionResolution  := Seq(
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
    releaseIOMonorepoHooksAfterReleaseVersionWrite := Seq(
      MonorepoProjectHookIO.transform("switch-late-bound-file-dependency") { (_, ctx) =>
        IO.blocking {
          val persistentState = ReleaseSessionOps.appendSessionSettings(
            ctx.state,
            Seq(ThisBuild / lateBoundVersionFileParts := Seq("next-version"))
          )
          // A public empty append still rebuilds `structure` while retaining
          // the exact SessionSettings instance. The following cross-build
          // switch must recognize that canonical rebuild instead of rejecting
          // the stale bridge marker as a same-key structure replacement.
          val emptyAppendState = Project
            .extract(persistentState)
            .appendWithSession(Seq.empty, persistentState)
          ctx.withState(emptyAppendState)
        }
      }
    ),
    checkLateBoundVersionFiles                     := {
      def assertProject(
          name: String,
          expectedRelease: String,
          expectedNext: String
      ): Unit = {
        val releaseVersion = sbt.IO.read(file(s"$name/version.properties")).trim
        val nextVersion    = sbt.IO.read(file(s"$name/next-version.properties")).trim
        val scopedVersion  = sbt.IO.read(file(s"$name/version.sbt")).trim
        assert(
          releaseVersion == expectedRelease,
          s"Unexpected $name/version.properties: '$releaseVersion' (expected '$expectedRelease')"
        )
        assert(
          nextVersion == expectedNext,
          s"Unexpected $name/next-version.properties: '$nextVersion' (expected '$expectedNext')"
        )
        assert(
          scopedVersion.contains("""version := "0.1.0-SNAPSHOT""""),
          s"$name/version.sbt should stay unchanged, but was: $scopedVersion"
        )
      }
      // The promoted resolver retains both the non-definitive project update
      // and its delegated ThisBuild base: release versions land in
      // version.properties, then the post-write hook changes the base and next
      // versions land in next-version.properties.
      assertProject("core", "1.0.0", "1.1.0-SNAPSHOT")
      assertProject("api", "2.0.0", "2.1.0-SNAPSHOT")
    }
  )
