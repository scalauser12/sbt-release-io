import scala.sys.process.*
import _root_.io.release.monorepo.MonorepoProjectHookIO

lazy val base = (project in file("base"))
  .settings(name := "base", scalaVersion := "2.12.18")

lazy val left = (project in file("left"))
  .dependsOn(base)
  .settings(name := "left", scalaVersion := "2.12.18")

lazy val right = (project in file("right"))
  .dependsOn(base)
  .settings(name := "right", scalaVersion := "2.12.18")

lazy val top = (project in file("top"))
  .dependsOn(left, right)
  .settings(name := "top", scalaVersion := "2.12.18")

val checkAll = taskKey[Unit]("Run all verification checks")
val recordOrderHook = MonorepoProjectHookIO.action("record-order") { (_, project) =>
  _root_.cats.effect.IO.blocking {
    val writer = new java.io.FileWriter(file("order.txt"), true)
    writer.write(project.name + "\n")
    writer.close()
  }
}

lazy val root = (project in file("."))
  .aggregate(base, left, right, top)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                          := "diamond-dependency-test",
    releaseIOMonorepoBeforeTagHooks := Seq(recordOrderHook),
    releaseIOIgnoreUntrackedFiles   := true,
    releaseIOMonorepoEnablePublish  := false,
    releaseIOMonorepoEnablePush     := false,
    releaseIOMonorepoEnableRunClean := false,
    releaseIOMonorepoEnableRunTests := false,
    checkAll                      := {
      // Check execution order
      val marker = baseDirectory.value / "order.txt"
      assert(marker.exists, s"order.txt marker file does not exist at ${marker.getAbsolutePath}")
      val lines  = IO.readLines(marker).filter(_.nonEmpty)
      assert(
        lines.length == 4,
        s"Expected 4 entries but got ${lines.length}: ${lines.mkString(", ")}"
      )
      assert(lines.head == "base", s"Expected 'base' first but got '${lines.head}'")
      assert(lines.last == "top", s"Expected 'top' last but got '${lines.last}'")
      val middle = lines.slice(1, 3).sorted
      assert(
        middle == List("left", "right"),
        s"Expected middle entries [left, right] but got [${middle.mkString(", ")}]"
      )

      // Check git tags
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 4, s"Expected 4 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("base/v0.1.0", "left/v0.1.0", "right/v0.1.0", "top/v0.1.0"),
        s"Expected tags [base/v0.1.0, left/v0.1.0, right/v0.1.0, top/v0.1.0] but got [${tags.mkString(", ")}]"
      )
    }
  )
