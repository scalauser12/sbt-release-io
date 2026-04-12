import scala.sys.process.*
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO
import _root_.io.release.monorepo.MonorepoProjectHookIO

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

def appendLine(file: File, line: String): Unit =
  sbt.IO.append(file, line + "\n")

def plainAfterSelectionHook(base: File): MonorepoGlobalHookIO =
  MonorepoGlobalHookIO(
    name = "plain-after-selection",
    execute = ctx =>
      IO.blocking {
        appendLine(base / "global-execute-order.log", "plain-global-execute")
        sbt.IO.touch(base / "plain-global-execute.marker")
        ctx
      },
    validate = _ =>
      IO.blocking {
        appendLine(base / "global-validate-order.log", "plain-global-validate")
        sbt.IO.touch(base / "plain-global-validate.marker")
      }
  )

def plainAfterTagHook(base: File): MonorepoProjectHookIO =
  MonorepoProjectHookIO(
    name = "plain-after-tag",
    execute = (ctx, project) =>
      IO.blocking {
        appendLine(base / "project-execute-order.log", s"${project.name}:plain-project-execute")
        sbt.IO.touch(project.baseDir / "plain-after-tag-execute.marker")
        ctx
      },
    validate = (_, project) =>
      IO.blocking {
        appendLine(base / "project-validate-order.log", s"${project.name}:plain-project-validate")
        sbt.IO.touch(project.baseDir / "plain-after-tag-validate.marker")
      }
  )

val checkGlobalValidateOrder  = taskKey[Unit]("Check global validate order")
val checkProjectValidateOrder = taskKey[Unit]("Check per-project validate order")
val checkGlobalExecuteOrder   = taskKey[Unit]("Check global execute order")
val checkProjectExecuteOrder  = taskKey[Unit]("Check per-project execute order")
val checkTag                  = taskKey[Unit]("Check git tags")
val checkCoreVersion          = taskKey[Unit]("Check core version.sbt")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(CustomReleasePlugin)
  .settings(
    name                                 := "custom-plugin-resource-hooks-monorepo",
    releaseIOVcsIgnoreUntrackedFiles     := true,
    releaseIOMonorepoPolicyEnablePublish := false,
    releaseIOMonorepoPolicyEnablePush    := false,
    releaseIOMonorepoHooksAfterSelection := Seq(plainAfterSelectionHook(baseDirectory.value)),
    releaseIOMonorepoHooksAfterTag       := Seq(plainAfterTagHook(baseDirectory.value)),
    checkGlobalValidateOrder             := {
      val lines = sbt.IO.readLines(baseDirectory.value / "global-validate-order.log")
      assert(
        lines == List("plain-global-validate", "resource-global-validate"),
        s"Unexpected global validate order: ${lines.mkString(", ")}"
      )
    },
    checkProjectValidateOrder            := {
      val lines = sbt.IO.readLines(baseDirectory.value / "project-validate-order.log")
      assert(
        lines == List("core:plain-project-validate", "core:resource-project-validate"),
        s"Unexpected project validate order: ${lines.mkString(", ")}"
      )
    },
    checkGlobalExecuteOrder              := {
      val lines = sbt.IO.readLines(baseDirectory.value / "global-execute-order.log")
      assert(
        lines == List("plain-global-execute", "resource-global-execute"),
        s"Unexpected global execute order: ${lines.mkString(", ")}"
      )
    },
    checkProjectExecuteOrder             := {
      val lines = sbt.IO.readLines(baseDirectory.value / "project-execute-order.log")
      assert(
        lines == List("core:plain-project-execute", "core:resource-project-execute"),
        s"Unexpected project execute order: ${lines.mkString(", ")}"
      )
    },
    checkTag                             := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
      assert(tags == List("core/v1.0.0"), s"Unexpected git tags: ${tags.mkString(", ")}")
    },
    checkCoreVersion                     := {
      val contents = sbt.IO.read(file("core/version.sbt"))
      assert(
        contents.contains("1.1.0-SNAPSHOT"),
        s"Expected core version 1.1.0-SNAPSHOT but got: $contents"
      )
    }
  )
