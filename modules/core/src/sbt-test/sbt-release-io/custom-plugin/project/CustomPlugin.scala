import sbt.*
import sbt.Keys.*
import _root_.io.release.*
import cats.effect.{IO, Resource}

object CustomPlugin extends ReleasePluginIOLike[java.io.File] {
  override def trigger = noTrigger

  // Use a distinct command name to coexist with ReleasePluginIO
  override protected def commandName: String = "releaseCustom"

  override def resource: Resource[IO, java.io.File] = {
    val marker = new java.io.File(System.getProperty("user.dir"), "resource-acquired")
    Resource.make(
      IO { sbt.IO.touch(marker); marker }
    )(_ =>
      IO { sbt.IO.touch(new java.io.File(System.getProperty("user.dir"), "resource-released")) }
    )
  }

  override protected def releaseProcess(state: State): Seq[java.io.File => ReleaseStepIO] =
    liftSteps(Project.extract(state).get(releaseIOProcess)) :+ ((acquired: java.io.File) =>
      ReleaseStepIO(
        name = "use-resource",
        execute = (ctx: ReleaseContext) =>
          IO {
            assert(acquired.exists(), s"Resource should exist: ${acquired.getAbsolutePath}")
            sbt.IO.touch(new java.io.File(System.getProperty("user.dir"), "step-used-resource"))
            ctx
          }
      )
    )
}
