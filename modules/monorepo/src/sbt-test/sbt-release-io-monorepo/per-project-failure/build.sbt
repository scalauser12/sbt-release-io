import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name                                   := "core",
    scalaVersion                           := "2.12.18",
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name                                   := "api",
    scalaVersion                           := "2.12.18",
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )

val checkFailureArtifacts =
  taskKey[Unit]("Verify run-tests failure stops all later release mutations")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                           := "per-project-failure-test",
    releaseIOVcsIgnoreUntrackedFiles  := true,
    releaseIOMonorepoPolicyEnablePublish := false,
    releaseIOMonorepoPolicyEnablePush    := false,
    checkFailureArtifacts          := {
      val commitCount = "git rev-list --count HEAD".!!.trim.toInt
      assert(
        commitCount == 1,
        s"Expected only the initial commit after failure, found $commitCount"
      )

      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(tags.isEmpty, s"Expected no tags after failure but found: ${tags.mkString(", ")}")

      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("""version := "0.1.0-SNAPSHOT""""),
        s"Expected core/version.sbt to remain at 0.1.0-SNAPSHOT but got: $coreContents"
      )

      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("""version := "0.1.0-SNAPSHOT""""),
        s"Expected api/version.sbt to remain at 0.1.0-SNAPSHOT but got: $apiContents"
      )
    }
  )
