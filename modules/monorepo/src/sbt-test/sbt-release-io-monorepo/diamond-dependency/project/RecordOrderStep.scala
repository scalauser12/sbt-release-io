import _root_.io.release.monorepo.{MonorepoStepIO, MonorepoContext, ProjectReleaseInfo}

object RecordOrderStep {

  val step: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "record-order",
    action = (ctx: MonorepoContext, project: ProjectReleaseInfo) =>
      _root_.cats.effect.IO {
        val rootBase = sbt.Project.extract(ctx.state).get(sbt.Keys.baseDirectory)
        val marker   = new java.io.File(rootBase, "order.txt")
        val writer   = new java.io.FileWriter(marker, true)
        writer.write(project.name + "\n")
        writer.close()
        ctx
      }
  )
}
