import cats.effect.{IO, Resource}
import _root_.io.release.monorepo.*
import _root_.io.release.monorepo.MonorepoReleaseIO.*
import sbt.*

object LateBoundMonorepoVersionPlugin extends MonorepoReleasePluginLike[Unit] {
  override def trigger = noTrigger

  override protected def commandName: String = "releaseLateBoundMonorepoVersion"

  override def resource: Resource[IO, Unit] = Resource.unit

  override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
    defaultsWithBefore(state, "inquire-versions")((_: Unit) =>
      MonorepoStepIO.Global(
        name = "late-bound-version-settings",
        execute = ctx =>
          IO.blocking {
            val extracted    = Project.extract(ctx.state)
            val updatedState = extracted.appendWithSession(
              Seq(
                releaseIOMonorepoVersionFile  := { (ref: ProjectRef, state: State) =>
                  Project.extract(state).get(ref / sbt.Keys.baseDirectory) / "version.properties"
                },
                releaseIOMonorepoReadVersion  := { file =>
                  IO.blocking(sbt.IO.read(file).trim)
                },
                releaseIOMonorepoWriteVersion := { (_, version) =>
                  IO.pure(version + "\n")
                }
              ),
              ctx.state
            )
            sbt.IO.touch(extracted.get(sbt.Keys.baseDirectory) / "late-bound-version-settings-ran")
            ctx.withState(updatedState)
          }
      )
    )
}
