import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.ReleaseSessionOps
import _root_.io.release.monorepo.MonorepoProjectHookIO

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

val checkLateBoundVersionFile = taskKey[Unit]("Check the late-bound version file")
val checkLateBoundTag         = taskKey[Unit]("Check the late-bound tag")

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
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                          := "hook-late-bound-settings-monorepo",
    releaseIOVcsIgnoreUntrackedFiles              := true,
    releaseIOMonorepoPolicyEnableRunTests         := false,
    releaseIOMonorepoPolicyEnablePublish          := false,
    releaseIOMonorepoPolicyEnablePush             := false,
    releaseIOMonorepoHooksBeforeVersionResolution := Seq(
      MonorepoProjectHookIO.transform("late-bound-version-settings") { (_, ctx) =>
        IO.blocking {
          // ReleaseSessionOps.appendSessionSettings installs the late-bound
          // version-file mapping into `session.rawAppend` so it survives every
          // subsequent `appendWithSession` call (set/commit/tag steps).
          val updatedState = ReleaseSessionOps.appendSessionSettings(
            ctx.state,
            lateBoundVersionSettings
          )
          val baseDir      = Project.extract(updatedState).get(baseDirectory)
          sbt.IO.touch(baseDir / "late-bound-version-settings-ran")
          ctx.withState(updatedState)
        }
      }
    ),
    releaseIOMonorepoHooksBeforeTag               := Seq(
      MonorepoProjectHookIO.transform("late-bound-tag-settings") { (_, ctx) =>
        IO.blocking {
          val updatedState = ReleaseSessionOps.appendSessionSettings(
            ctx.state,
            lateBoundVersionSettings ++ Seq(
              releaseIOMonorepoVcsTagName := ((_: String, _: String) => "late-bound-runtime-tag")
            )
          )
          val baseDir      = Project.extract(updatedState).get(baseDirectory)
          sbt.IO.touch(baseDir / "late-bound-tag-settings-ran")
          ctx.withState(updatedState)
        }
      }
    ),
    checkLateBoundVersionFile                     := {
      val runtimeVersion = sbt.IO.read(file("core/version.properties")).trim
      val scopedVersion  = sbt.IO.read(file("core/version.sbt")).trim

      assert(
        runtimeVersion == "1.1.0-SNAPSHOT",
        s"Unexpected core/version.properties: $runtimeVersion"
      )
      assert(
        scopedVersion.contains("""version := "0.2.0-SNAPSHOT""""),
        s"core/version.sbt should stay unchanged, but was: $scopedVersion"
      )
    },
    checkLateBoundTag                             := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
      assert(tags == List("late-bound-runtime-tag"), s"Unexpected tags: ${tags.mkString(", ")}")
    }
  )
