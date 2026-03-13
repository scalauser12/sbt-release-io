import cats.effect.{IO, Resource}
import _root_.io.release.*
import sbt.*

object LateBoundTagPlugin extends ReleasePluginIOLike[Unit] {
  override def trigger = noTrigger

  override protected def commandName: String = "releaseLateBoundTag"

  override def resource: Resource[IO, Unit] = Resource.unit

  override protected def releaseProcess(state: State): Seq[Unit => ReleaseStepIO] =
    defaultsWithBefore(state, "tag-release")((_: Unit) =>
      ReleaseStepIO.io("late-bound-tag-settings") { ctx =>
        IO.blocking {
          val extracted    = Project.extract(ctx.state)
          val updatedState = extracted.appendWithSession(
            Seq(
              _root_.io.release.ReleaseIO.releaseIOTagName := "late-bound-runtime-tag"
            ),
            ctx.state
          )
          sbt.IO.touch(extracted.get(sbt.Keys.baseDirectory) / "late-bound-tag-settings-ran")
          ctx.withState(updatedState)
        }
      }
    )
}
