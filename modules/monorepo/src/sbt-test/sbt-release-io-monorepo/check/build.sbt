import sbt.IO

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, TimeoutException, blocking}
import scala.sys.process.*

val expectExplicitCheckSuccess =
  taskKey[Unit]("Run an explicit monorepo check and assert the preflight summary")
val expectDetectChangesCheckSuccess =
  taskKey[Unit]("Run a detect-changes monorepo check and assert the preflight summary")
val expectUnknownOverrideFailure =
  taskKey[Unit]("Run monorepo check with an unknown override target and assert the failure output")
val expectMissingVersionFileFailure =
  taskKey[Unit]("Run monorepo check without a project version file and assert the failure output")
val expectZeroChangedProjectsFailure =
  taskKey[Unit]("Run monorepo check with zero detected projects and assert the failure output")
val expectTagCollisionFailure =
  taskKey[Unit]("Run monorepo check with an existing tag and assert the failure output")

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

def versionFiles(): Seq[File] =
  Seq(file("core/version.sbt"), file("api/version.sbt"))

def snapshot(): (Int, List[String], Map[String, Option[String]]) =
  (
    gitCommitCount(),
    gitTags(),
    versionFiles().map { versionFile =>
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

def assertNestedRun(
    commands: Seq[String],
    outputFile: File,
    shouldSucceed: Boolean,
    expectedSubstrings: Seq[String],
    sbtVersion0: String,
    workingDir: File
): String = {
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
  output
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
    name := "check-monorepo-test",
    releaseIOMonorepoEnablePublish := false,
    releaseIOMonorepoEnablePush    := false,
    releaseIOIgnoreUntrackedFiles := true,
    expectExplicitCheckSuccess := {
      assertNestedRun(
        commands = Seq(
          "releaseIOMonorepo check core with-defaults release-version core=0.1.0 next-version core=0.2.0-SNAPSHOT"
        ),
        outputFile = target.value / "explicit-check.log",
        shouldSucceed = true,
        expectedSubstrings = Seq(
          "selection mode: explicit selection",
          "core: release 0.1.0, next 0.2.0-SNAPSHOT",
          "tag core/v0.1.0 (available)",
          "Preflight checks passed."
        ),
        sbtVersion0 = sbtVersion.value,
        workingDir = baseDirectory.value
      )
      ()
    },
    expectDetectChangesCheckSuccess := {
      val output = assertNestedRun(
        commands = Seq("releaseIOMonorepo check with-defaults"),
        outputFile = target.value / "detect-changes-check.log",
        shouldSucceed = true,
        expectedSubstrings = Seq(
          "selection mode: detect changes",
          "core: release 0.2.0, next 0.2.1-SNAPSHOT",
          "Preflight checks passed."
        ),
        sbtVersion0 = sbtVersion.value,
        workingDir = baseDirectory.value
      )

      assert(
        !output.contains("api: release"),
        s"Expected detect-changes check to exclude api, but it did not.\n$output"
      )
    },
    expectUnknownOverrideFailure := {
      assertNestedRun(
        commands = Seq(
          "releaseIOMonorepo check core with-defaults release-version missing=0.1.0 next-version missing=0.2.0-SNAPSHOT"
        ),
        outputFile = target.value / "unknown-override.log",
        shouldSucceed = false,
        expectedSubstrings = Seq(
          "Unknown projects in version overrides: missing",
          "releaseIOMonorepo help"
        ),
        sbtVersion0 = sbtVersion.value,
        workingDir = baseDirectory.value
      )
      ()
    },
    expectMissingVersionFileFailure := {
      assertNestedRun(
        commands = Seq(
          "releaseIOMonorepo check core with-defaults release-version core=0.1.0 next-version core=0.2.0-SNAPSHOT"
        ),
        outputFile = target.value / "missing-version-file.log",
        shouldSucceed = false,
        expectedSubstrings = Seq(
          "Version file not found for core",
          "releaseIOMonorepo help"
        ),
        sbtVersion0 = sbtVersion.value,
        workingDir = baseDirectory.value
      )
      ()
    },
    expectZeroChangedProjectsFailure := {
      assertNestedRun(
        commands = Seq("releaseIOMonorepo check with-defaults"),
        outputFile = target.value / "zero-changed-projects.log",
        shouldSucceed = false,
        expectedSubstrings = Seq(
          "No projects have changed since their last release tag",
          "all-changed",
          "releaseIOMonorepo help"
        ),
        sbtVersion0 = sbtVersion.value,
        workingDir = baseDirectory.value
      )
      ()
    },
    expectTagCollisionFailure := {
      assertNestedRun(
        commands = Seq(
          "releaseIOMonorepo check core with-defaults release-version core=0.1.0 next-version core=0.2.0-SNAPSHOT"
        ),
        outputFile = target.value / "tag-collision.log",
        shouldSucceed = false,
        expectedSubstrings = Seq(
          "Current settings would abort in use-defaults mode",
          "releaseIOMonorepo help"
        ),
        sbtVersion0 = sbtVersion.value,
        workingDir = baseDirectory.value
      )
      ()
    }
  )
