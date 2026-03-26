import scala.sys.process.*
import sbt.*
import sbt.Keys.*
import _root_.io.release.ReleaseHookIO

name := "hook-late-bound-settings"

scalaVersion := "2.12.18"

releaseIOIgnoreUntrackedFiles := true
releaseIOEnableRunTests       := false
releaseIOEnablePublish        := false
releaseIOEnablePush           := false

def lateBoundVersionSettings(runtimeVersion: File): Seq[Setting[?]] =
  Seq(
    releaseIOVersionFile         := runtimeVersion,
    releaseIOReadVersion         := { file =>
      _root_.cats.effect.IO.blocking(sbt.IO.read(file).trim)
    },
    releaseIOVersionFileContents := { (_, version) =>
      _root_.cats.effect.IO.pure(version + "\n")
    }
  )

releaseIOBeforeVersionResolutionHooks := Seq(
  ReleaseHookIO.io("late-bound-version-settings") { ctx =>
    _root_.cats.effect.IO.blocking {
      val extracted      = Project.extract(ctx.state)
      val base           = extracted.get(baseDirectory)
      val runtimeVersion = base / "version.properties"
      val updatedState   = extracted.appendWithSession(
        lateBoundVersionSettings(runtimeVersion),
        ctx.state
      )
      sbt.IO.touch(base / "late-bound-version-settings-ran")
      ctx.withState(updatedState)
    }
  }
)

releaseIOBeforeTagHooks := Seq(
  ReleaseHookIO.io("late-bound-tag-settings") { ctx =>
    _root_.cats.effect.IO.blocking {
      val extracted    = Project.extract(ctx.state)
      val base         = extracted.get(baseDirectory)
      val runtimeVersion = base / "version.properties"
      val updatedState = extracted.appendWithSession(
        lateBoundVersionSettings(runtimeVersion) ++ Seq(
          releaseIOTagName := "late-bound-runtime-tag"
        ),
        ctx.state
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
