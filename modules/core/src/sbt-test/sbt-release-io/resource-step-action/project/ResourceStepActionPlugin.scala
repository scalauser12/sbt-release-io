import sbt.*
import sbt.Keys.*
import _root_.io.release.*
import cats.effect.{IO, Resource}

object ResourceStepActionPlugin extends ReleasePluginIOLike[java.io.File] {
  override def trigger = noTrigger

  override protected def commandName: String = "releaseResourceAction"

  override def resource: Resource[IO, java.io.File] = {
    val marker = new java.io.File(System.getProperty("user.dir"), "resource-acquired")
    Resource.make(IO { sbt.IO.touch(marker); marker })(_ =>
      IO { sbt.IO.touch(new java.io.File(System.getProperty("user.dir"), "resource-released")) }
    )
  }

  override protected def releaseProcess(state: State): Seq[java.io.File => ReleaseStepIO] =
    liftSteps(Project.extract(state).get(releaseIOProcess)) ++ Seq(
      // Test resourceStepAction (execute returns IO[Unit])
      resourceStepAction("action-step")((f: java.io.File) =>
        (ctx: ReleaseContext) =>
          IO {
            assert(f.exists(), s"Resource should exist: ${f.getAbsolutePath}")
            sbt.IO.write(
              new java.io.File(System.getProperty("user.dir"), "action-ran"),
              "ran"
            )
          }
      ),
      // Test resourceStepActionWithValidation (both return IO[Unit])
      resourceStepActionWithValidation("action-with-validation")((f: java.io.File) =>
        (ctx: ReleaseContext) =>
          IO {
            sbt.IO.write(
              new java.io.File(System.getProperty("user.dir"), "action-validated-ran"),
              "ran"
            )
          }
      )((f: java.io.File) =>
        (_: ReleaseContext) =>
          IO {
            assert(f.exists(), s"Resource should exist: ${f.getAbsolutePath}")
            sbt.IO.write(
              new java.io.File(System.getProperty("user.dir"), "validation-ran"),
              "ran"
            )
          }
      )
    )
}
