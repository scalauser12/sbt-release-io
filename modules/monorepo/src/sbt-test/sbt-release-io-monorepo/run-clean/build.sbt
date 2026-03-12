import scala.sys.process.*

val seedStaleFile          = taskKey[Unit]("Create a stale file under the active target")
val assertStaleFileExists  = taskKey[Unit]("Assert the stale file exists under the active target")
val assertStaleFileCleaned = taskKey[Unit]("Assert the stale file was removed by run-clean")
val checkReleaseSelection  = taskKey[Unit]("Verify only the selected project was released")

def staleFile(base: File): File = base / "stale.txt"

lazy val staleFileTasks = Seq(
  seedStaleFile := {
    val file = staleFile(target.value)
    IO.createDirectory(file.getParentFile)
    IO.write(file, "stale")
  },
  assertStaleFileExists := {
    val file = staleFile(target.value)
    assert(file.exists, s"Expected stale file to exist at ${file.getAbsolutePath}")
  },
  assertStaleFileCleaned := {
    val file = staleFile(target.value)
    assert(!file.exists, s"Expected stale file to be removed from ${file.getAbsolutePath}")
  }
)

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )
  .settings(staleFileTasks)

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )
  .settings(staleFileTasks)

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "run-clean-monorepo-test",

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkReleaseSelection := {
      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("0.2.0-SNAPSHOT"),
        s"Expected core version 0.2.0-SNAPSHOT in core/version.sbt but got: $coreContents"
      )

      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("0.1.0-SNAPSHOT"),
        s"Expected api version 0.1.0-SNAPSHOT in api/version.sbt but got: $apiContents"
      )

      val tags = "git tag".!!.trim.linesIterator.filter(_.nonEmpty).toList
      assert(
        tags == List("core/v0.1.0"),
        s"Expected only core/v0.1.0 tag but got: ${tags.mkString(", ")}"
      )
    }
  )
