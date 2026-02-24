import _root_.cats.effect.{IO, Resource}
import _root_.io.release.monorepo.*
import sbt._

object CustomReleasePlugin extends MonorepoReleasePluginLike[java.io.File] {
  override def trigger = noTrigger

  override protected def commandName: String = "customRelease"

  override def resource: Resource[IO, java.io.File] = {
    val marker = new java.io.File(System.getProperty("user.dir"), "resource-acquired")
    Resource.make(
      IO { sbt.IO.touch(marker); marker }
    )(_ =>
      IO { sbt.IO.touch(new java.io.File(System.getProperty("user.dir"), "resource-released")) }
    )
  }

  private def useResource(acquired: java.io.File): MonorepoStepIO =
    MonorepoStepIO.Global(
      name = "use-resource",
      action = ctx =>
        IO {
          assert(acquired.exists(), s"Resource should exist: ${acquired.getAbsolutePath}")
          sbt.IO.touch(new java.io.File(System.getProperty("user.dir"), "step-used-resource"))
          ctx
        }
    )

  override protected def additionalSteps: Seq[java.io.File => MonorepoStepIO] =
    Seq((f: java.io.File) => useResource(f))
}
