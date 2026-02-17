package io.release

import cats.effect.unsafe.implicits.global
import io.release.steps.ReleaseSteps
import sbt._
import sbt.Keys._

object ReleaseIOPlugin extends AutoPlugin {

  override def trigger = allRequirements

  object autoImport {
    val releaseIOSteps = settingKey[Seq[ReleaseStepIO]]("The sequence of IO release steps to execute")
    val releaseIOSkipTests = settingKey[Boolean]("Whether to skip tests during release")
    val releaseIOSkipPublish = settingKey[Boolean]("Whether to skip publish during release")
    val releaseIO = Command.command("releaseIO")(doReleaseIO)
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    releaseIOSteps := ReleaseSteps.defaults,
    releaseIOSkipTests := false,
    releaseIOSkipPublish := false,
    commands += releaseIO
  )

  private def doReleaseIO(state: State): State = {
    val extracted = Project.extract(state)
    val steps = extracted.get(releaseIOSteps)
    val skipTests = extracted.get(releaseIOSkipTests)
    val skipPublish = extracted.get(releaseIOSkipPublish)

    val initialCtx = ReleaseContext(
      state = state,
      skipTests = skipTests,
      skipPublish = skipPublish
    )

    // Compose all steps into a single IO program and run once
    val program = ReleaseStepIO.compose(steps)

    println("[release-io] Starting release process...")
    println(s"[release-io] ${steps.length} steps to execute")

    val finalCtx = program(initialCtx).unsafeRunSync()

    println("[release-io] Release completed successfully!")
    finalCtx.state
  }
}
