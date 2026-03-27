import scala.sys.process.*
import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

name := "custom-plugin-resource-hooks"

scalaVersion := "2.12.18"

releaseIOIgnoreUntrackedFiles := true
releaseIOEnablePublish        := false
releaseIOEnablePush           := false

enablePlugins(CustomPlugin)

def appendLine(file: File, line: String): Unit =
  sbt.IO.append(file, line + "\n")

def plainBeforeTagHook(base: File): ReleaseHookIO =
  ReleaseHookIO(
    name = "plain-before-tag",
    execute = ctx =>
      IO.blocking {
        appendLine(base / "execute-order.log", "plain-execute")
        sbt.IO.touch(base / "plain-execute.marker")
        ctx
      },
    validate = _ =>
      IO.blocking {
        appendLine(base / "validate-order.log", "plain-validate")
        sbt.IO.touch(base / "plain-validate.marker")
      }
  )

releaseIOBeforeTagHooks := Seq(plainBeforeTagHook(baseDirectory.value))

val checkValidateOrder = taskKey[Unit]("Check validate order for beforeTag hooks")
checkValidateOrder := {
  val lines = sbt.IO.readLines(baseDirectory.value / "validate-order.log")
  assert(
    lines == List("plain-validate", "resource-validate"),
    s"Unexpected validate order: ${lines.mkString(", ")}"
  )
}

val checkExecuteOrder = taskKey[Unit]("Check execute order for beforeTag hooks")
checkExecuteOrder := {
  val lines = sbt.IO.readLines(baseDirectory.value / "execute-order.log")
  assert(
    lines == List("plain-execute", "resource-execute"),
    s"Unexpected execute order: ${lines.mkString(", ")}"
  )
}

val checkGitTag = taskKey[Unit]("Check that the default release tag was created")
checkGitTag := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(tags == List("v0.1.0"), s"Unexpected git tags: ${tags.mkString(", ")}")
}

val checkNextVersion =
  taskKey[Unit]("Check that version.sbt was updated to the next snapshot version")
checkNextVersion := {
  val contents = sbt.IO.read(file("version.sbt"))
  assert(
    contents.contains("""version := "0.2.0-SNAPSHOT""""),
    s"""Expected version.sbt to contain 'version := "0.2.0-SNAPSHOT"' but got: $contents"""
  )
}
