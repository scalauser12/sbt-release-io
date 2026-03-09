import _root_.cats.effect.{IO, Resource}
import _root_.io.release.monorepo.*
import _root_.io.release.monorepo.MonorepoReleaseIO.*
import sbt.*

object LateBoundProjectsPlugin extends MonorepoReleasePluginLike[Unit] {
  override def trigger = noTrigger

  override protected def commandName: String = "releaseLateBoundProjects"

  override def resource: Resource[IO, Unit] = Resource.unit

  override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
    defaultsWithBefore(state, "resolve-release-order")((_: Unit) =>
      MonorepoStepIO.Global(
        name = "late-bound-projects-setting",
        action = ctx =>
          IO.blocking {
            val extracted    = Project.extract(ctx.state)
            val baseDir      = extracted.get(sbt.Keys.baseDirectory)
            val updatedState = extracted.appendWithSession(
              Seq(
                releaseIOMonorepoProjects := Seq(ProjectRef(baseDir, "core"))
              ),
              ctx.state
            )
            sbt.IO.touch(extracted.get(sbt.Keys.baseDirectory) / "late-bound-projects-setting-ran")
            ctx.withState(updatedState)
          }
      )
    )
}
