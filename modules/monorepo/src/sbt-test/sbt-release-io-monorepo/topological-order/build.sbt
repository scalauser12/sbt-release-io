import scala.sys.process.*
import _root_.io.release.monorepo.MonorepoProjectHookIO

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val middle = (project in file("middle"))
  .dependsOn(core)
  .settings(
    name         := "middle",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .dependsOn(middle)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

val checkAll        = taskKey[Unit]("Run all verification checks")
val recordOrderHook = MonorepoProjectHookIO.action("record-order") { (_, project) =>
  _root_.cats.effect.IO.blocking {
    val writer = new java.io.FileWriter(file("order.txt"), true)
    writer.write(project.name + "\n")
    writer.close()
  }
}

lazy val root = (project in file("."))
  .aggregate(core, middle, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "topological-order-test",

    releaseIOMonorepoHooksBeforeTag       := Seq(recordOrderHook),
    releaseIOVcsIgnoreUntrackedFiles      := true,
    releaseIOMonorepoPolicyEnablePublish  := false,
    releaseIOMonorepoPolicyEnablePush     := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoPolicyEnableRunTests := false,

    checkAll := {
      // Check execution order
      val marker = baseDirectory.value / "order.txt"
      assert(marker.exists, s"order.txt marker file does not exist at ${marker.getAbsolutePath}")
      val lines  = IO.readLines(marker).filter(_.nonEmpty)
      // Topological order: core (no deps) -> middle (depends on core) -> api (depends on middle)
      assert(
        lines == List("core", "middle", "api"),
        s"Expected order [core, middle, api] but got [${lines.mkString(", ")}]"
      )

      // Check git tags
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 3, s"Expected 3 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("api/v0.1.0", "core/v0.1.0", "middle/v0.1.0"),
        s"Expected tags [api/v0.1.0, core/v0.1.0, middle/v0.1.0] but got [${tags.mkString(", ")}]"
      )
    }
  )
