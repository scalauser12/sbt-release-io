import scala.sys.process.Process

name := "exit-code-test"
scalaVersion := "2.12.18"

val failingTask = taskKey[Unit]("A task that always fails")
failingTask := { throw new IllegalStateException("This task always fails") }

def checkExitCode(baseDir: File, releaseProcessSetting: String, expected: Int): Unit = {
  val pluginVersion =
    sys.props.getOrElse("plugin.version", sys.error("plugin.version system property is required"))

  val command = Seq(
    "sbt",
    s"-Dplugin.version=$pluginVersion",
    "-batch",
    s"set releaseIOProcess := $releaseProcessSetting",
    "releaseIO with-defaults"
  )

  val exitValue = Process(command, baseDir).!
  println(s"Exit code is $exitValue and should be $expected for: $releaseProcessSetting")
  assert(
    exitValue == expected,
    s"Expected exit code $expected but got $exitValue for releaseIOProcess = $releaseProcessSetting"
  )
}

val checkExitCodes = taskKey[Unit]("Check releaseIO process exit codes")
checkExitCodes := {
  val baseDir = baseDirectory.value

  checkExitCode(baseDir, """Seq(_root_.io.release.ReleaseStepIO.fromCommand("show version"))""", 0)
  checkExitCode(baseDir, """Seq(_root_.io.release.ReleaseStepIO.fromCommand("failingTask"))""", 1)
  checkExitCode(
    baseDir,
    """Seq(_root_.io.release.ReleaseStepIO.fromCommand("show version"), _root_.io.release.ReleaseStepIO.fromCommand("failingTask"))""",
    1
  )
  checkExitCode(
    baseDir,
    """Seq(_root_.io.release.ReleaseStepIO.fromCommandAndRemaining("show version"))""",
    0
  )
  checkExitCode(
    baseDir,
    """Seq(_root_.io.release.ReleaseStepIO.fromCommandAndRemaining("failingTask"))""",
    1
  )
}
