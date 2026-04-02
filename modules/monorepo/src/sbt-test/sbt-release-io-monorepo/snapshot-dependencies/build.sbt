import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name                          := "core",
    scalaVersion                  := "2.12.18",
    // Override releaseIODiagnosticsSnapshotDependencies to simulate a SNAPSHOT dependency
    releaseIODiagnosticsSnapshotDependencies := Seq(
      "org.example" %% "fake-lib" % "1.0.0-SNAPSHOT"
    )
  )

val checkNoCommits = taskKey[Unit]("Verify no release commits were made")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                          := "snapshot-dependencies-test",
    releaseIOMonorepoPolicyEnablePublish := false,
    releaseIOMonorepoPolicyEnablePush    := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles := true,
    checkNoCommits                := {
      val count = "git log --oneline".!!.trim.split("\n").length
      assert(count == 1, s"Expected 1 commit (initial only) but found $count")
    }
  )
