import sbt.IO

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, TimeoutException, blocking}
import scala.sys.process.*

val expectCheckWithoutInquireTagSuccess =
  taskKey[Unit]("Run releaseIO check without built-in version/tag steps and assert the summary")

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

name         := "check-without-inquire-tag-test"
scalaVersion := "2.12.18"

releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  Set("inquire-versions", "tag-release", "push-changes", "publish-artifacts").contains(step.name)
}

releaseIOIgnoreUntrackedFiles := true

expectCheckWithoutInquireTagSuccess := {
  val before             = snapshot()
  val (exitCode, output) = runNestedSbt(
    nestedBaseCommand(sbtVersion.value) ++ Seq(
      "releaseIO check with-defaults release-version 0.1.0 next-version 0.2.0-SNAPSHOT"
    ),
    target.value / "check-without-inquire-tag.log",
    baseDirectory.value
  )

  assert(exitCode == 0, s"Expected nested sbt command to succeed but got exit code $exitCode")
  Seq(
    "version file   : not evaluated (inquire-versions not in check process)",
    "current version: not evaluated (inquire-versions not in check process)",
    "release version: not evaluated (inquire-versions not in check process)",
    "next version   : not evaluated (inquire-versions not in check process)",
    "tag            : not evaluated (tag-release not in check process)",
    "Preflight checks passed."
  ).foreach { expected =>
    assert(
      output.contains(expected),
      s"Expected nested sbt output to include '$expected', but it did not.\n$output"
    )
  }

  assertUnchanged(before)
}
