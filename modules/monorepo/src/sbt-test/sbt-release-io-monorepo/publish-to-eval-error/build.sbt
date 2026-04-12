import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, TimeoutException, blocking}
import scala.sys.process.*

val checkNoReleaseChanges      = taskKey[Unit]("Verify no release commit or tag was created")
val expectPublishToEvalFailure =
  taskKey[Unit]("Run release and assert publishTo evaluation failure")

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

def nestedBaseCommand(sbtVersion0: String, pluginVersion0: String): Seq[String] = {
  val sbtScript = sys.props.getOrElse("sbt.script", "sbt")

  Seq(
    sbtScript,
    "--server",
    s"-Dsbt.version=$sbtVersion0",
    s"-Dplugin.version=$pluginVersion0"
  ) ++
    forwardedNestedJvmArgs ++
    Seq(
      "-Dsbt.log.noformat=true",
      "-batch"
    )
}

lazy val core = (project in file("core"))
  .settings(
    name         := "publish-to-eval-error-core",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                                   := "publish-to-eval-error-test",
    releaseIOVcsIgnoreUntrackedFiles                       := true,
    releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck := false,
    releaseIOMonorepoPolicyEnablePush                      := false,
    releaseIOMonorepoPolicyEnableRunClean                  := false,
    releaseIOMonorepoPolicyEnableRunTests                  := false,
    expectPublishToEvalFailure                             := {
      val sbtVersionProp     = sbtVersion.value
      val pluginVersionProp  =
        sys.props.getOrElse("plugin.version", sys.error("plugin.version not set"))
      val outputFile         = file("out.log")
      val (exitCode, output) = runNestedSbt(
        nestedBaseCommand(sbtVersionProp, pluginVersionProp) ++ Seq(
          "set core / publishTo := sys.error(\"publishTo exploded\")",
          "releaseIOMonorepo core with-defaults release-version core=0.1.0 next-version core=0.2.0-SNAPSHOT"
        ),
        outputFile,
        baseDirectory.value
      )

      assert(exitCode != 0, "Expected nested sbt release command to fail")
      assert(
        output.contains("Failed to evaluate publishTo for core"),
        s"Expected wrapped publishTo evaluation failure message. Output was:\n$output"
      )
      assert(
        output.contains("publishTo exploded"),
        s"Expected original publishTo error message. Output was:\n$output"
      )
    },
    checkNoReleaseChanges                                  := {
      val commits = "git log --oneline".!!.trim.linesIterator.toList
      assert(commits.length == 1, s"Expected 1 commit (initial only) but found ${commits.length}")

      val tags = "git tag".!!.trim
      assert(tags.isEmpty, s"Expected no git tags but found: $tags")
    }
  )
