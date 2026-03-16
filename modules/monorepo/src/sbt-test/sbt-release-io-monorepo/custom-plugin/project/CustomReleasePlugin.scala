import cats.effect.{IO, Resource}
import _root_.io.release.monorepo.*
import _root_.io.release.monorepo.MonorepoReleaseIO.*
import sbt.*

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

  override protected def monorepoReleaseProcess(
      state: State
  ): Seq[java.io.File => MonorepoStepIO] =
    liftSteps(Project.extract(state).get(releaseIOMonorepoProcess)) :+ ((acquired: java.io.File) =>
      MonorepoStepIO.Global(
        name = "use-resource",
        execute = ctx =>
          IO {
            assert(acquired.exists(), s"Resource should exist: ${acquired.getAbsolutePath}")
            sbt.IO.touch(new java.io.File(System.getProperty("user.dir"), "step-used-resource"))
            ctx
          }
      )
    )
}
