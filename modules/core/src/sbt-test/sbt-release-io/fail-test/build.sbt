import sbt.IO

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException, blocking}
import scala.sys.process._

val expectReleaseFailure =
  taskKey[Unit]("Run nested release and assert the attributed failure output")

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

name         := "fail-test-test"
scalaVersion := "2.12.18"

libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test
testFrameworks += new TestFramework("munit.Framework")

expectReleaseFailure := {
  val outputFile        = target.value / "release.log"
  val pluginVersionProp =
    sys.props.getOrElse("plugin.version", sys.error("plugin.version not set"))
  val sbtScript         = sys.props.getOrElse("sbt.script", "sbt")
  val (exitCode, output) =
    runNestedSbt(
      Seq(
        sbtScript,
        "--server",
        s"-Dsbt.version=${sbtVersion.value}",
        s"-Dplugin.version=$pluginVersionProp",
        "-Dsbt.log.noformat=true",
        "-batch",
        "releaseIO"
      ),
      outputFile,
      baseDirectory.value
    )

  assert(exitCode != 0, "Expected nested sbt release command to fail")
  assert(
    output.contains("Release failed: run-tests:"),
    s"Expected attributed run-tests failure message in nested sbt output:\n$output"
  )
  assert(
    output.contains("This test is designed to fail"),
    s"Expected nested sbt output to include the original failing test message:\n$output"
  )
}

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnablePublish        := false
releaseIOPolicyEnablePush           := false
