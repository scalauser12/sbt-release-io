import sbt._
import sbt.Keys._
import _root_.io.release._
import _root_.cats.effect.{IO, Resource}

object DefaultsWithAfterPlugin extends ReleasePluginIOLike[Unit] {
  override def trigger = noTrigger

  override protected def commandName: String = "releaseDefaultsAfter"

  override def resource: Resource[IO, Unit] = Resource.unit

  override protected def releaseProcess(state: State): Seq[Unit => ReleaseStepIO] =
    defaultsWithAfter(state, "check-clean-working-dir")((_: Unit) =>
      ReleaseStepIO.io("inserted-after-check") { ctx =>
        IO {
          // Write to project root; target/ may not exist before runClean
          val marker =
            new java.io.File(System.getProperty("user.dir"), "inserted-after-check")
          sbt.IO.write(marker, "inserted")
          ctx
        }
      }
    )
}
