import cats.effect.{IO, Resource}
import _root_.io.release.*
import sbt.*

object LateBoundVersionPlugin extends ReleasePluginIOLike[Unit] {
  override def trigger = noTrigger

  override protected def commandName: String = "releaseLateBoundVersion"

  override def resource: Resource[IO, Unit] = Resource.unit

  override protected def releaseProcess(state: State): Seq[Unit => ReleaseStepIO] =
    insertBefore(Project.extract(state).get(releaseIOProcess), "inquire-versions")(
      Seq((_: Unit) =>
        ReleaseStepIO.io("late-bound-version-settings") { ctx =>
          IO.blocking {
            val extracted      = Project.extract(ctx.state)
            val baseDir        = extracted.get(sbt.Keys.baseDirectory)
            val runtimeVersion = baseDir / "version.properties"
            val updatedState   = extracted.appendWithSession(
              Seq(
                _root_.io.release.ReleaseIO.releaseIOVersionFile := runtimeVersion,
                releaseIOReadVersion                             := { file =>
                  IO.blocking(sbt.IO.read(file).trim)
                },
                releaseIOWriteVersion                            := { (_, version) =>
                  IO.pure(version + "\n")
                }
              ),
              ctx.state
            )
            sbt.IO.touch(baseDir / "late-bound-version-settings-ran")
            ctx.withState(updatedState)
          }
        }
      )
    )
}
