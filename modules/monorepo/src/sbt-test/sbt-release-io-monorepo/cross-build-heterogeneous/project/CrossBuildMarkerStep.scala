import _root_.io.release.monorepo.{MonorepoStepIO, MonorepoContext, ProjectReleaseInfo}

object CrossBuildMarkerStep {

  val step: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "write-cross-markers",
    action = (ctx: MonorepoContext, project: ProjectReleaseInfo) => {
      val extracted = sbt.Project.extract(ctx.state)
      val sv        = extracted.get(sbt.Keys.scalaVersion)
      val markerDir = new java.io.File(project.baseDir, "marker")
      val marker    = new java.io.File(markerDir, s"built-$sv")
      sbt.IO.touch(marker)
      val counter   = new java.io.File(markerDir, "invocations.txt")
      sbt.IO.append(counter, sv + "\n")
      _root_.cats.effect.IO.pure(ctx)
    },
    enableCrossBuild = true
  )
}
