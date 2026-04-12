import sbt.IO

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, TimeoutException, blocking}
import scala.sys.process.*

val expectMonorepoHelp =
  taskKey[Unit]("Run releaseIOMonorepo help in a nested sbt and assert the output")

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

def gitCommitCount(): Int =
  "git rev-list --count HEAD".!!.trim.toInt

def gitTags(): List[String] =
  "git tag --list".!!.trim.linesIterator.map(_.trim).filter(_.nonEmpty).toList.sorted

def versionFiles(): Seq[File] =
  Seq(file("core/version.sbt"), file("api/version.sbt"))

def snapshot(): (Int, List[String], Map[String, String]) =
  (
    gitCommitCount(),
    gitTags(),
    versionFiles().map(f => f.getPath -> IO.read(f)).toMap
  )

def assertUnchanged(before: (Int, List[String], Map[String, String])): Unit = {
  val after = snapshot()

  assert(after._1 == before._1, s"Expected commit count ${before._1} but found ${after._1}")
  assert(after._2 == before._2, s"Expected git tags ${before._2} but found ${after._2}")
  assert(
    after._3 == before._3,
    "Expected project version files to remain unchanged during releaseIOMonorepo help"
  )
}

def forwardedNestedJvmArgs: Seq[String] =
  Seq(
    "sbt.ivy.home",
    "sbt.boot.directory",
    "sbt.global.base",
    "sbt.repository.config",
    "sbt.override.build.repos"
  ).flatMap(key => sys.props.get(key).map(value => s"-D$key=$value"))

def nestedBaseCommand(sbtVersion0: String): Seq[String] = {
  val pluginVersionProp = sys.props.getOrElse("plugin.version", sys.error("plugin.version not set"))
  val sbtScript         = sys.props.getOrElse("sbt.script", "sbt")

  Seq(
    sbtScript,
    "--server",
    s"-Dsbt.version=$sbtVersion0",
    s"-Dplugin.version=$pluginVersionProp"
  ) ++
    forwardedNestedJvmArgs ++
    Seq(
      "-Dsbt.log.noformat=true",
      "-batch"
    )
}

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

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                             := "help-monorepo-test",
    releaseIOVcsIgnoreUntrackedFiles := true,
    expectMonorepoHelp               := {
      val before             = snapshot()
      val outputFile         = target.value / "monorepo-help.log"
      val (exitCode, output) =
        runNestedSbt(
          nestedBaseCommand(sbtVersion.value) ++ Seq("releaseIOMonorepo help"),
          outputFile,
          baseDirectory.value
        )

      assert(
        exitCode == 0,
        s"Expected releaseIOMonorepo help to succeed but got exit code $exitCode"
      )

      val expectedSubstrings = Seq(
        """Usage: sbt "releaseIOMonorepo [selectors] [flags] [version overrides]""",
        "No release side effects: no version-file writes, commits, tags, publish, or push",
        "may temporarily switch Scala versions during validation and then restore the entry version",
        "release-version <project>=<version>",
        "Use project <id> when a project id collides with a CLI keyword or subcommand",
        "https://github.com/scalauser12/sbt-release-io/blob/main/docs/monorepo/README.md"
      )

      expectedSubstrings.foreach { expected =>
        assert(
          output.contains(expected),
          s"Expected nested sbt output to include '$expected', but it did not.\n$output"
        )
      }

      val coreHelpOutputFile              = target.value / "core-help.log"
      val (coreHelpExitCode, coreHelpOut) =
        runNestedSbt(
          nestedBaseCommand(sbtVersion.value) ++ Seq("releaseIO help"),
          coreHelpOutputFile,
          baseDirectory.value
        )

      assert(coreHelpExitCode == 0, s"Expected releaseIO help to succeed but got $coreHelpExitCode")
      assert(
        coreHelpOut.contains("""Usage: sbt "releaseIO [flags]"""),
        s"Expected nested sbt output to include core help usage, but it did not.\n$coreHelpOut"
      )

      val extraOutputFile              = target.value / "monorepo-help-extra.log"
      val (extraExitCode, extraOutput) =
        runNestedSbt(
          nestedBaseCommand(sbtVersion.value) ++ Seq("releaseIOMonorepo help extra"),
          extraOutputFile,
          baseDirectory.value
        )

      assert(extraExitCode != 0, "Expected releaseIOMonorepo help extra to fail")
      assert(extraOutput.nonEmpty, "Expected releaseIOMonorepo help extra to produce parser output")

      assertUnchanged(before)
    }
  )
