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
      // Test builder executeAction (execute returns IO[Unit])
      ReleaseStepIO
        .resourceStep[java.io.File]("action-step")
        .executeAction(f =>
          ctx =>
            IO {
              assert(f.exists(), s"Resource should exist: ${f.getAbsolutePath}")
              sbt.IO.write(
                new java.io.File(System.getProperty("user.dir"), "action-ran"),
                "ran"
              )
            }
        ),
      // Test builder withValidation + executeAction
      ReleaseStepIO
        .resourceStep[java.io.File]("action-with-validation")
        .withValidation(f =>
          _ =>
            IO {
              assert(f.exists(), s"Resource should exist: ${f.getAbsolutePath}")
              sbt.IO.write(
                new java.io.File(System.getProperty("user.dir"), "validation-ran"),
                "ran"
              )
            }
        )
        .executeAction(f =>
          ctx =>
            IO {
              sbt.IO.write(
                new java.io.File(System.getProperty("user.dir"), "action-validated-ran"),
                "ran"
              )
            }
        )
    )
}
