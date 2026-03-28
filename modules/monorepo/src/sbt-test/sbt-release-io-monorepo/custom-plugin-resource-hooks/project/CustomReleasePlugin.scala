import cats.effect.{IO, Resource}
import _root_.io.release.monorepo.*
import sbt.*

object CustomReleasePlugin extends MonorepoReleasePluginLike[java.io.File] {
  override def trigger = noTrigger

  override protected def commandName: String = "customResourceHooks"

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

  override protected def monorepoResourceHooks(
      state: State
  ): MonorepoResourceHooks[java.io.File] =
    MonorepoResourceHooks(
      afterSelectionHooks = Seq(
        MonorepoGlobalResourceHookIO[java.io.File](
          name = "resource-after-selection",
          execute = base => ctx =>
            IO.blocking {
              appendLine(new java.io.File(base, "global-execute-order.log"), "resource-global-execute")
              sbt.IO.touch(new java.io.File(base, "resource-global-execute.marker"))
              ctx
            },
          validate = _ =>
            IO.blocking {
              appendLine(rootFile("global-validate-order.log"), "resource-global-validate")
              sbt.IO.touch(rootFile("resource-global-validate.marker"))
            }
        )
      ),
      afterTagHooks = Seq(
        MonorepoProjectResourceHookIO[java.io.File](
          name = "resource-after-tag",
          execute = base => (ctx, project) =>
            IO.blocking {
              appendLine(
                new java.io.File(base, "project-execute-order.log"),
                s"${project.name}:resource-project-execute"
              )
              sbt.IO.touch(project.baseDir / "resource-after-tag-execute.marker")
              ctx
            },
          validate = (_, project) =>
            IO.blocking {
              appendLine(
                rootFile("project-validate-order.log"),
                s"${project.name}:resource-project-validate"
              )
              sbt.IO.touch(project.baseDir / "resource-after-tag-validate.marker")
            }
        )
      )
    )
}
