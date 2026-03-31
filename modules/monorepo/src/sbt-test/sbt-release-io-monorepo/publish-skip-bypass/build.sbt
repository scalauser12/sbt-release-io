import scala.sys.process.*
import _root_.io.release.monorepo.MonorepoProjectHookIO

lazy val core = (project in file("core"))
  .settings(
    name           := "core",
    scalaVersion   := "2.12.18",
    // publishTo is intentionally NOT set
    // But publish / skip := true bypasses the publishTo check
    publish / skip := true
  )

val checkGitTags     = taskKey[Unit]("Check git tags")
val checkCoreVersion = taskKey[Unit]("Check core version.sbt")
val checkPublishHooksSkipped = taskKey[Unit]("Check publish hooks were skipped for publish / skip")

val beforePublishMarkerHook = MonorepoProjectHookIO.action("before-publish-marker") { (_, project) =>
  _root_.cats.effect.IO.blocking {
    val markerDir = project.baseDir / "marker"
    IO.createDirectory(markerDir)
    IO.touch(markerDir / "before-publish.txt")
  }
}

val afterPublishMarkerHook = MonorepoProjectHookIO.action("after-publish-marker") { (_, project) =>
  _root_.cats.effect.IO.blocking {
    val markerDir = project.baseDir / "marker"
    IO.createDirectory(markerDir)
    IO.touch(markerDir / "after-publish.txt")
  }
}

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                          := "publish-skip-bypass-test",
    // Keep publish-artifacts in process (only filter push-changes)
    releaseIOMonorepoEnablePush    := false,
    releaseIOMonorepoEnableRunClean := false,
    releaseIOMonorepoEnableRunTests := false,
    releaseIOMonorepoBeforePublishHooks := Seq(beforePublishMarkerHook),
    releaseIOMonorepoAfterPublishHooks  := Seq(afterPublishMarkerHook),
    releaseIOIgnoreUntrackedFiles := true,
    checkGitTags                  := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(tags.length == 1, s"Expected 1 tag but found ${tags.length}: ${tags.mkString(", ")}")
      assert(tags.head == "core/v0.1.0", s"Expected tag core/v0.1.0 but got ${tags.head}")
    },
    checkCoreVersion              := {
      val contents = IO.read(file("core/version.sbt"))
      assert(
        contents.contains("0.2.0-SNAPSHOT"),
        s"Expected core version 0.2.0-SNAPSHOT but got: $contents"
      )
    },
    checkPublishHooksSkipped      := {
      val markerDir = file("core/marker")
      assert(
        !(markerDir / "before-publish.txt").exists(),
        "before-publish hook should be skipped when publish / skip := true"
      )
      assert(
        !(markerDir / "after-publish.txt").exists(),
        "after-publish hook should be skipped when publish / skip := true"
      )
    }
  )
