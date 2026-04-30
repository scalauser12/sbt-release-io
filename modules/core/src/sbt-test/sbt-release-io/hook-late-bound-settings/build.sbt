import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO
import _root_.io.release.ReleaseSessionOps

name := "hook-late-bound-settings"

scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnableRunTests    := false
releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnablePush        := false

def lateBoundVersionSettings(runtimeVersion: File): Seq[Setting[?]] =
  Seq(
    releaseIOVersioningFile         := runtimeVersion,
    releaseIOVersioningReadVersion  := { file =>
      _root_.cats.effect.IO.blocking(sbt.IO.read(file).trim)
    },
    releaseIOVersioningFileContents := { (_, version) =>
      _root_.cats.effect.IO.pure(version + "\n")
    }
  )

releaseIOHooksBeforeVersionResolution := Seq(
  ReleaseHookIO.transform("late-bound-version-settings") { ctx =>
    _root_.cats.effect.IO.blocking {
      val base           = Project.extract(ctx.state).get(baseDirectory)
      val runtimeVersion = base / "version.properties"
      // ReleaseSessionOps.appendSessionSettings installs the late-bound
      // version-file mapping into `session.rawAppend` so it survives every
      // subsequent `appendWithSession` call (set/commit/tag steps).
      val updatedState   = ReleaseSessionOps.appendSessionSettings(
        ctx.state,
        lateBoundVersionSettings(runtimeVersion)
      )
      sbt.IO.touch(base / "late-bound-version-settings-ran")
      ctx.withState(updatedState)
    }
  }
)

releaseIOHooksBeforeTag := Seq(
  ReleaseHookIO.transform("late-bound-tag-settings") { ctx =>
    _root_.cats.effect.IO.blocking {
      val base           = Project.extract(ctx.state).get(baseDirectory)
      val runtimeVersion = base / "version.properties"
      val updatedState   = ReleaseSessionOps.appendSessionSettings(
        ctx.state,
        lateBoundVersionSettings(runtimeVersion) ++ Seq(
          releaseIOVcsTagName := "late-bound-runtime-tag"
        )
      )
      sbt.IO.touch(base / "late-bound-tag-settings-ran")
      ctx.withState(updatedState)
    }
  }
)

val checkRuntimeVersionFile = taskKey[Unit]("Check the late-bound version file")
checkRuntimeVersionFile := {
  val runtimeVersion = IO.read(file("version.properties")).trim
  val rootVersion    = IO.read(file("version.sbt")).trim

  assert(runtimeVersion == "0.2.0-SNAPSHOT", s"Unexpected version.properties: $runtimeVersion")
  assert(
    rootVersion.contains("""version := "0.1.0-SNAPSHOT""""),
    s"version.sbt should stay unchanged, but was: $rootVersion"
  )
}

val checkLateBoundTag = taskKey[Unit]("Check the late-bound tag")
checkLateBoundTag := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(tags == List("late-bound-runtime-tag"), s"Unexpected tags: ${tags.mkString(", ")}")
}
