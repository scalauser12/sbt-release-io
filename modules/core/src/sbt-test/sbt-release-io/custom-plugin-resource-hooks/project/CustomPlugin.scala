import cats.effect.{IO, Resource}
import _root_.io.release.*
import sbt.*

object CustomPlugin extends ReleasePluginIOLike[java.io.File] {
  override def trigger = noTrigger

  override protected def commandName: String = "releaseResourceHooks"

  private def rootDir: java.io.File =
    new java.io.File(System.getProperty("user.dir"))

  private def rootFile(name: String): java.io.File =
    new java.io.File(rootDir, name)

  private def appendLine(file: java.io.File, line: String): Unit =
    sbt.IO.append(file, line + "\n")

  override def resource: Resource[IO, java.io.File] =
    Resource.make(
      IO.blocking {
        sbt.IO.touch(rootFile("resource-acquired"))
        rootDir
      }
    )(_ =>
      IO.blocking {
        sbt.IO.touch(rootFile("resource-released"))
      }
    )

  override protected def releaseResourceHooks(
      state: State
  ): ReleaseResourceHooks[java.io.File] =
    ReleaseResourceHooks(
      beforeTagHooks = Seq(
        ReleaseResourceHookIO[java.io.File](
          name = "resource-before-tag",
          execute = base => ctx =>
            IO.blocking {
              appendLine(new java.io.File(base, "execute-order.log"), "resource-execute")
              sbt.IO.touch(new java.io.File(base, "resource-execute.marker"))
              ctx
            },
          validate = _ =>
            IO.blocking {
              appendLine(rootFile("validate-order.log"), "resource-validate")
              sbt.IO.touch(rootFile("resource-validate.marker"))
            }
        )
      )
    )
}
