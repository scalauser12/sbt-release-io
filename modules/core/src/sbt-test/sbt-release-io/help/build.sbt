import sbt.IO

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, TimeoutException, blocking}
import scala.sys.process.*

val expectReleaseHelp = taskKey[Unit]("Run releaseIO help in a nested sbt and assert the output")

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

def snapshot(): (Int, List[String], String) =
  (
    gitCommitCount(),
    gitTags(),
    IO.read(file("version.sbt"))
  )

def assertUnchanged(before: (Int, List[String], String)): Unit = {
  val after = snapshot()

  assert(after._1 == before._1, s"Expected commit count ${before._1} but found ${after._1}")
  assert(after._2 == before._2, s"Expected git tags ${before._2} but found ${after._2}")
  assert(
    after._3 == before._3,
    "Expected version.sbt to remain unchanged during releaseIO help"
  )
}

name         := "help-test"
scalaVersion := "2.12.18"

releaseIOVcsIgnoreUntrackedFiles := true

expectReleaseHelp := {
  val before             = snapshot()
  val outputFile         = target.value / "release-help.log"
  val pluginVersionProp  =
    sys.props.getOrElse("plugin.version", sys.error("plugin.version not set"))
  val sbtScript          = sys.props.getOrElse("sbt.script", "sbt")
  val (exitCode, output) =
    runNestedSbt(
      Seq(
        sbtScript,
        "--server",
        s"-Dsbt.version=${sbtVersion.value}",
        s"-Dplugin.version=$pluginVersionProp",
        "-Dsbt.log.noformat=true",
        "-batch",
        "releaseIO help"
      ),
      outputFile,
      baseDirectory.value
    )

  assert(exitCode == 0, s"Expected releaseIO help to succeed but got exit code $exitCode")

  val expectedSubstrings = Seq(
    """Usage: sbt "releaseIO [flags]""",
    """releaseIO check [flags]""",
    "No release side effects: no version-file writes, commits, tags, publish, or push",
    "may temporarily switch Scala versions during validation and then restore the entry version",
    "Versions and tags are summarized only when runtime hook state cannot still change them",
    "A readable version file (default: version.sbt)",
    "default-tag-exists-answer <o|k|a|<tag-name>>",
    "Default flow:",
    "https://github.com/scalauser12/sbt-release-io/blob/main/docs/core/README.md"
  )

  expectedSubstrings.foreach { expected =>
    assert(
      output.contains(expected),
      s"Expected nested sbt output to include '$expected', but it did not.\n$output"
    )
  }

  val extraOutputFile              = target.value / "release-help-extra.log"
  val (extraExitCode, extraOutput) =
    runNestedSbt(
      Seq(
        sbtScript,
        "--server",
        s"-Dsbt.version=${sbtVersion.value}",
        s"-Dplugin.version=$pluginVersionProp",
        "-Dsbt.log.noformat=true",
        "-batch",
        "releaseIO help extra"
      ),
      extraOutputFile,
      baseDirectory.value
    )

  assert(extraExitCode != 0, "Expected releaseIO help extra to fail")
  assert(extraOutput.nonEmpty, "Expected releaseIO help extra to produce parser output")

  assertUnchanged(before)
}
