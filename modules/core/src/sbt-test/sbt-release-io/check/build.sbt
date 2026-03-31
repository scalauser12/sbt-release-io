import sbt.IO

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, TimeoutException, blocking}
import scala.sys.process.*

val expectCheckSuccess =
  taskKey[Unit]("Run releaseIO check successfully and assert the preflight summary")
val expectCheckMissingVersionFileFailure =
  taskKey[Unit]("Run releaseIO check without a version file and assert the failure output")
val expectCheckDirtyWorkingTreeFailure =
  taskKey[Unit]("Run releaseIO check with a dirty working tree and assert the failure output")
val expectCheckInvalidVersionFailure =
  taskKey[Unit]("Run releaseIO check with invalid CLI version input and assert the failure output")
val expectCheckMissingPublishToFailure =
  taskKey[Unit]("Run releaseIO check without publishTo and assert the failure output")
val expectCheckTagCollisionFailure =
  taskKey[Unit]("Run releaseIO check with an existing tag and assert the failure output")

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
      val output = outputBuffer.result()
      IO.write(outputFile, output)
      sys.error(timeoutMessage)
  }
}

def gitCommitCount(): Int =
  "git rev-list --count HEAD".!!.trim.toInt

def gitTags(): List[String] =
  "git tag --list".!!.trim.linesIterator.map(_.trim).filter(_.nonEmpty).toList.sorted

def readVersionFile(): Option[String] = {
  val versionFile = file("version.sbt")
  if (versionFile.exists()) Some(IO.read(versionFile)) else None
}

def snapshot(): (Int, List[String], Option[String]) =
  (
    gitCommitCount(),
    gitTags(),
    readVersionFile()
  )

def assertUnchanged(before: (Int, List[String], Option[String])): Unit = {
  val after = snapshot()

  assert(after._1 == before._1, s"Expected commit count ${before._1} but found ${after._1}")
  assert(after._2 == before._2, s"Expected git tags ${before._2} but found ${after._2}")
  assert(
    after._3 == before._3,
    "Expected version.sbt to remain unchanged during releaseIO check"
  )
}

def nestedBaseCommand(sbtVersion0: String): Seq[String] = {
  val pluginVersionProp = sys.props.getOrElse("plugin.version", sys.error("plugin.version not set"))
  val sbtScript         = sys.props.getOrElse("sbt.script", "sbt")

  Seq(
    sbtScript,
    "--server",
    s"-Dsbt.version=$sbtVersion0",
    s"-Dplugin.version=$pluginVersionProp",
    "-Dsbt.log.noformat=true",
    "-batch"
  )
}

def assertNestedRun(
    commands: Seq[String],
    outputFile: File,
    shouldSucceed: Boolean,
    expectedSubstrings: Seq[String],
    sbtVersion0: String,
    workingDir: File
): Unit = {
  val before            = snapshot()
  val (exitCode, output) =
    runNestedSbt(nestedBaseCommand(sbtVersion0) ++ commands, outputFile, workingDir)

  if (shouldSucceed)
    assert(exitCode == 0, s"Expected nested sbt command to succeed but got exit code $exitCode")
  else
    assert(exitCode != 0, "Expected nested sbt command to fail")

  expectedSubstrings.foreach { expected =>
    assert(
      output.contains(expected),
      s"Expected nested sbt output to include '$expected', but it did not.\n$output"
    )
  }

  assertUnchanged(before)
}

name         := "check-test"
scalaVersion := "2.12.18"

releaseIOEnablePush := false
releaseIOIgnoreUntrackedFiles := true
publishTo := Some(Resolver.file("test-repo", target.value / "repo"))

expectCheckSuccess := {
  assertNestedRun(
    commands = Seq(
      "releaseIO check with-defaults release-version 0.1.0 next-version 0.2.0-SNAPSHOT"
    ),
    outputFile = target.value / "check-success.log",
    shouldSucceed = true,
    expectedSubstrings = Seq(
      "Preflight summary:",
      "release version: 0.1.0",
      "next version   : 0.2.0-SNAPSHOT",
      "tag            : v0.1.0 (available)",
      "publish        : enabled",
      "push           : step not configured",
      "Preflight checks passed."
    ),
    sbtVersion0 = sbtVersion.value,
    workingDir = baseDirectory.value
  )
}

expectCheckMissingVersionFileFailure := {
  assertNestedRun(
    commands = Seq(
      "releaseIO check with-defaults release-version 0.1.0 next-version 0.2.0-SNAPSHOT"
    ),
    outputFile = target.value / "check-missing-version-file.log",
    shouldSucceed = false,
    expectedSubstrings = Seq(
      "Version file not found",
      "releaseIOVersionFile",
      "releaseIO help"
    ),
    sbtVersion0 = sbtVersion.value,
    workingDir = baseDirectory.value
  )
}

expectCheckDirtyWorkingTreeFailure := {
  assertNestedRun(
    commands = Seq(
      "releaseIO check with-defaults release-version 0.1.0 next-version 0.2.0-SNAPSHOT"
    ),
    outputFile = target.value / "check-dirty-working-tree.log",
    shouldSucceed = false,
    expectedSubstrings = Seq(
      "unstaged modified files",
      "tracked.txt"
    ),
    sbtVersion0 = sbtVersion.value,
    workingDir = baseDirectory.value
  )
}

expectCheckInvalidVersionFailure := {
  assertNestedRun(
    commands = Seq(
      "releaseIO check with-defaults release-version banana next-version 0.2.0-SNAPSHOT"
    ),
    outputFile = target.value / "check-invalid-version.log",
    shouldSucceed = false,
    expectedSubstrings = Seq(
      "Invalid version format: 'banana'",
      "See the command help for examples."
    ),
    sbtVersion0 = sbtVersion.value,
    workingDir = baseDirectory.value
  )
}

expectCheckMissingPublishToFailure := {
  assertNestedRun(
    commands = Seq(
      "set publishTo := None",
      "releaseIO check with-defaults release-version 0.1.0 next-version 0.2.0-SNAPSHOT"
    ),
    outputFile = target.value / "check-missing-publishto.log",
    shouldSucceed = false,
    expectedSubstrings = Seq(
      "publishTo not configured",
      "publish / skip := true"
    ),
    sbtVersion0 = sbtVersion.value,
    workingDir = baseDirectory.value
  )
}

expectCheckTagCollisionFailure := {
  assertNestedRun(
    commands = Seq(
      "releaseIO check with-defaults release-version 0.1.0 next-version 0.2.0-SNAPSHOT"
    ),
    outputFile = target.value / "check-tag-collision.log",
    shouldSucceed = false,
    expectedSubstrings = Seq(
      "Current settings would abort in use-defaults mode",
      "releaseIO help"
    ),
    sbtVersion0 = sbtVersion.value,
    workingDir = baseDirectory.value
  )
}
