import scala.sys.process.*

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                                  := "no-changes-without-upstream-test",
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,
    releaseIOVcsIgnoreUntrackedFiles      := true,

    checkAll := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(
        tags.toList == List("api/v0.1.0", "core/v0.1.0"),
        s"Expected only the original tags but got [${tags.mkString(", ")}]"
      )

      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("0.1.0-SNAPSHOT"),
        s"Expected core version unchanged at 0.1.0-SNAPSHOT but got: $coreContents"
      )

      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("0.1.0-SNAPSHOT"),
        s"Expected api version unchanged at 0.1.0-SNAPSHOT but got: $apiContents"
      )
    }
  )

val checkAll = taskKey[Unit]("Run all verification checks")
