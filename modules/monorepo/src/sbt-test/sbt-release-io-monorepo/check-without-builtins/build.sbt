import sbt.IO

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, TimeoutException, blocking}
import scala.sys.process.*

val expectCheckWithoutBuiltinsSuccess =
  taskKey[Unit]("Run releaseIOMonorepo check without built-in setup steps and assert the summary")

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

def snapshot(): (Int, List[String], Map[String, Option[String]]) =
  (
    gitCommitCount(),
    gitTags(),
    Seq(file("core/version.sbt")).map { versionFile =>
      val contents =
        if (versionFile.exists()) Some(IO.read(versionFile))
        else None
      versionFile.getPath -> contents
    }.toMap
  )

def assertUnchanged(before: (Int, List[String], Map[String, Option[String]])): Unit = {
  val after = snapshot()

  assert(after._1 == before._1, s"Expected commit count ${before._1} but found ${after._1}")
  assert(after._2 == before._2, s"Expected git tags ${before._2} but found ${after._2}")
  assert(
    after._3 == before._3,
    "Expected project version files to remain unchanged during releaseIOMonorepo check"
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

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "check-without-builtins-test",
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      Set(
        "detect-or-select-projects",
        "inquire-versions",
        "tag-releases",
        "push-changes",
        "publish-artifacts"
      ).contains(step.name)
    },
    releaseIOIgnoreUntrackedFiles := true,
    expectCheckWithoutBuiltinsSuccess := {
      val before             = snapshot()
      val (exitCode, output) = runNestedSbt(
        nestedBaseCommand(sbtVersion.value) ++ Seq(
          "releaseIOMonorepo check core with-defaults release-version core=0.1.0 next-version core=0.2.0-SNAPSHOT"
        ),
        target.value / "check-without-builtins.log",
        baseDirectory.value
      )

      assert(exitCode == 0, s"Expected nested sbt command to succeed but got exit code $exitCode")
      Seq(
        "selection mode: not evaluated (detect-or-select-projects not in check process)",
        "core: release not evaluated (inquire-versions not in check process), next not evaluated (inquire-versions not in check process), tag not evaluated (tag-releases not in check process)",
        "Preflight checks passed."
      ).foreach { expected =>
        assert(
          output.contains(expected),
          s"Expected nested sbt output to include '$expected', but it did not.\n$output"
        )
      }

      assertUnchanged(before)
    }
  )
