import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name                                   := "core",
    scalaVersion                           := "2.12.18",
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
    testFrameworks += new TestFramework("munit.Framework"),
    Test / fork                            := true,
    Test / javaOptions += s"-Dproject.base=${baseDirectory.value.getAbsolutePath}"
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name                                   := "api",
    scalaVersion                           := "2.12.18",
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
    testFrameworks += new TestFramework("munit.Framework"),
    Test / fork                            := true,
    Test / javaOptions += s"-Dproject.base=${baseDirectory.value.getAbsolutePath}"
  )

val checkAll = taskKey[Unit]("Run all verification checks")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                           := "skip-tests-setting-test",
    releaseIOMonorepoBehaviorSkipTests     := true,
    releaseIOVcsIgnoreUntrackedFiles  := true,
    releaseIOMonorepoPolicyEnablePublish := false,
    releaseIOMonorepoPolicyEnablePush    := false,
    checkAll                       := {
      val coreMarker = file("core/marker/tests-ran")
      assert(
        !coreMarker.exists(),
        s"core tests should have been skipped but marker exists at $coreMarker"
      )

      val apiMarker = file("api/marker/tests-ran")
      assert(
        !apiMarker.exists(),
        s"api tests should have been skipped but marker exists at $apiMarker"
      )

      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("api/v0.1.0", "core/v0.1.0"),
        s"Expected tags [api/v0.1.0, core/v0.1.0] but got [${tags.mkString(", ")}]"
      )

      val coreVer = IO.read(file("core/version.sbt"))
      assert(
        coreVer.contains("0.2.0-SNAPSHOT"),
        s"Expected core version 0.2.0-SNAPSHOT but got: $coreVer"
      )

      val apiVer = IO.read(file("api/version.sbt"))
      assert(
        apiVer.contains("0.2.0-SNAPSHOT"),
        s"Expected api version 0.2.0-SNAPSHOT but got: $apiVer"
      )
    }
  )
