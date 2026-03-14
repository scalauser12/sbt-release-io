import sbt.*
import sbt.Keys.*
import _root_.io.release.*
import cats.effect.{IO, Resource}

object ResourceStepWithCheckPlugin extends ReleasePluginIOLike[java.io.File] {
  override def trigger = noTrigger

  override protected def commandName: String = "releaseResourceCheck"

  override def resource: Resource[IO, java.io.File] = {
    val marker = new java.io.File(System.getProperty("user.dir"), "resource-acquired")
    Resource.make(IO { sbt.IO.touch(marker); marker })(_ =>
      IO { sbt.IO.touch(new java.io.File(System.getProperty("user.dir"), "resource-released")) }
    )
  }

  override protected def releaseProcess(state: State): Seq[java.io.File => ReleaseStepIO] =
    liftSteps(Project.extract(state).get(releaseIOProcess)) :+ (
      resourceStepWithValidation("resource-with-check")((f: java.io.File) =>
        (ctx: ReleaseContext) =>
          IO {
            sbt.IO.write(
              new java.io.File(System.getProperty("user.dir"), "action-ran"),
              "ran"
            )
            ctx
          }
      )((f: java.io.File) =>
        (_: ReleaseContext) =>
          IO {
            assert(f.exists(), s"Resource should exist: ${f.getAbsolutePath}")
            sbt.IO.write(
              new java.io.File(System.getProperty("user.dir"), "check-ran"),
              "ran"
            )
          }
      )
    )
}
