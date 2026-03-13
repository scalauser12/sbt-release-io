import cats.effect.{IO, Resource}
import _root_.io.release.monorepo.*
import _root_.io.release.monorepo.MonorepoReleaseIO.*
import sbt.*

object LateBoundDetectPlugin extends MonorepoReleasePluginLike[Unit] {
  override def trigger = noTrigger

  override protected def commandName: String = "releaseLateBoundDetect"

  override def resource: Resource[IO, Unit] = Resource.unit

  override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
    defaultsWithBefore(state, "detect-or-select-projects")((_: Unit) =>
      MonorepoStepIO.Global(
        name = "late-bound-detect-settings",
        execute = ctx =>
          IO.blocking {
            val extracted    = Project.extract(ctx.state)
            val updatedState = extracted.appendWithSession(
              Seq(
                releaseIOMonorepoDetectChanges := false
              ),
              ctx.state
            )
            sbt.IO.touch(extracted.get(sbt.Keys.baseDirectory) / "late-bound-detect-settings-ran")
            ctx.withState(updatedState)
          }
      )
    )
}
