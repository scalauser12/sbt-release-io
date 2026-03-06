import sbt._
import sbt.Keys._
import _root_.io.release._
import _root_.cats.effect.{IO, Resource}

object DefaultsWithBeforePlugin extends ReleasePluginIOLike[Unit] {
  override def trigger = noTrigger

  override protected def commandName: String = "releaseDefaultsBefore"

  override def resource: Resource[IO, Unit] = Resource.unit

  override protected def releaseProcess(state: State): Seq[Unit => ReleaseStepIO] =
    defaultsWithBefore(state, "tag-release")((_: Unit) =>
      ReleaseStepIO.io("inserted-before-tag") { ctx =>
        IO {
          // Write to project root; target/ may not exist before runClean
          val marker =
            new java.io.File(System.getProperty("user.dir"), "inserted-before-tag")
          sbt.IO.write(marker, "inserted")
          ctx
        }
      }
    )
}
