import sbt.IO

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, TimeoutException, blocking}
import scala.sys.process.*

val checkCoexistence =
  taskKey[Unit]("Run a nested build with both public plugins and verify shared-key coexistence")

val NestedSbtTimeout = 5.minutes

def runNestedSbt(command: Seq[String], outputFile: File, workingDir: File): (Int, String) = {
  import scala.concurrent.ExecutionContext.Implicits.global

  val outputBuffer = new StringBuilder
  val logger       = ProcessLogger(
    line => outputBuffer.append(line).append(System.lineSeparator()),
    line => outputBuffer.append(line).append(System.lineSeparator())
  )

  val process        = Process(command, workingDir).run(logger)
  val exitCodeFuture = Future(blocking(process.exitValue()))

  try {
    val exitCode = Await.result(exitCodeFuture, NestedSbtTimeout)
    val output   = outputBuffer.result()
    IO.write(outputFile, output)
    exitCode -> output
  } catch {
    case _: TimeoutException =>
      process.destroy()
      try Await.result(exitCodeFuture, 10.seconds)
      catch { case _: TimeoutException => () }

      val timeoutMessage =
        s"Nested sbt process timed out after ${NestedSbtTimeout.toMinutes} minutes"
      outputBuffer.append(timeoutMessage).append(System.lineSeparator())
      val output         = outputBuffer.result()
      IO.write(outputFile, output)
      sys.error(timeoutMessage)
  }
}

def forwardedNestedJvmArgs: Seq[String] =
  Seq(
    "sbt.ivy.home",
    "sbt.boot.directory",
    "sbt.global.base",
    "sbt.repository.config",
    "sbt.override.build.repos"
  ).flatMap(key => sys.props.get(key).map(value => s"-D$key=$value"))

def nestedBaseCommand(sbtVersion0: String, pluginVersion: String): Seq[String] = {
  val sbtScript = sys.props.getOrElse("sbt.script", "sbt")

  Seq(
    sbtScript,
    "--server",
    s"-Dsbt.version=$sbtVersion0",
    s"-Dplugin.version=$pluginVersion"
  ) ++
    forwardedNestedJvmArgs ++
    Seq(
      "-Dsbt.log.noformat=true",
      "-batch"
    )
}

def writeNestedBuild(base: File, sbtVersion0: String, pluginVersion: String): Unit = {
  val projectDir = base / "project"
  IO.createDirectory(projectDir)

  IO.write(projectDir / "build.properties", s"sbt.version=$sbtVersion0\n")
  IO.write(
    projectDir / "plugins.sbt",
    s"""|addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "$pluginVersion")
        |addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "$pluginVersion")
        |""".stripMargin
  )
  IO.write(
    base / "build.sbt",
    """|import _root_.io.release.ReleasePluginIO
       |import _root_.io.release.monorepo.MonorepoReleasePlugin
       |
       |lazy val root = (project in file("."))
       |  .enablePlugins(ReleasePluginIO, MonorepoReleasePlugin)
       |  .settings(
       |    name := "nested-coexisting-plugins",
       |    scalaVersion := "2.12.18",
       |    releaseIOVcsIgnoreUntrackedFiles := true
       |  )
       |""".stripMargin
  )
  IO.write(base / "version.sbt", "ThisBuild / version := \"0.1.0-SNAPSHOT\"\n")
}

name         := "coexisting-plugins-shared-keys"
scalaVersion := "2.12.18"

checkCoexistence := {
  val pluginVersion = sys.props.getOrElse("plugin.version", sys.error("plugin.version not set"))
  val nestedDir     = target.value / "coexisting-build"
  IO.delete(nestedDir)
  IO.createDirectory(nestedDir)
  writeNestedBuild(nestedDir, sbtVersion.value, pluginVersion)

  val showOutputFile             = target.value / "nested-show.log"
  val (showExitCode, showOutput) =
    runNestedSbt(
      nestedBaseCommand(sbtVersion.value, pluginVersion) ++
        Seq("show releaseIOVcsIgnoreUntrackedFiles"),
      showOutputFile,
      nestedDir
    )

  assert(showExitCode == 0, s"Expected nested show command to succeed but got $showExitCode")
  assert(
    showOutput.contains("[info] true"),
    s"Expected bare shared key to resolve to true, but output was:\n$showOutput"
  )

  val coreHelpOutputFile              = target.value / "nested-releaseio-help.log"
  val (coreHelpExitCode, coreHelpOut) =
    runNestedSbt(
      nestedBaseCommand(sbtVersion.value, pluginVersion) ++ Seq("releaseIO help"),
      coreHelpOutputFile,
      nestedDir
    )

  assert(coreHelpExitCode == 0, s"Expected releaseIO help to succeed but got $coreHelpExitCode")
  assert(
    coreHelpOut.contains("""Usage: sbt "releaseIO [flags]"""),
    s"Expected releaseIO help output, but got:\n$coreHelpOut"
  )

  val monorepoHelpOutputFile                  = target.value / "nested-releaseio-monorepo-help.log"
  val (monorepoHelpExitCode, monorepoHelpOut) =
    runNestedSbt(
      nestedBaseCommand(sbtVersion.value, pluginVersion) ++ Seq("releaseIOMonorepo help"),
      monorepoHelpOutputFile,
      nestedDir
    )

  assert(
    monorepoHelpExitCode == 0,
    s"Expected releaseIOMonorepo help to succeed but got $monorepoHelpExitCode"
  )
  assert(
    monorepoHelpOut.contains(
      """Usage: sbt "releaseIOMonorepo [selectors] [flags] [version overrides]"""
    ),
    s"Expected releaseIOMonorepo help output, but got:\n$monorepoHelpOut"
  )
}
