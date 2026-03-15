import _root_.io.release.monorepo.{MonorepoReleaseIO, MonorepoStepIO}
import cats.effect.IO

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "insert-step-test",

    releaseIOMonorepoProcess := {
      val withAfter = MonorepoReleaseIO.insertStepAfter(
        releaseIOMonorepoProcess.value.filterNot { step =>
          step.name == "push-changes" ||
          step.name == "publish-artifacts" ||
          step.name == "run-clean" ||
          step.name == "run-tests"
        },
        "set-release-version"
      )(
        Seq(
          MonorepoStepIO.Global(
            name = "after-set-version-marker",
            execute = ctx =>
              IO {
                val base = sbt.Project.extract(ctx.state).get(sbt.Keys.baseDirectory)
                sbt.IO.touch(base / "marker" / "after-set-version")
                ctx
              }
          )
        )
      )
      MonorepoReleaseIO.insertStepBefore(withAfter, "tag-releases")(
        Seq(
          MonorepoStepIO.Global(
            name = "before-tag-marker",
            execute = ctx =>
              IO {
                val base = sbt.Project.extract(ctx.state).get(sbt.Keys.baseDirectory)
                sbt.IO.touch(base / "marker" / "before-tag")
                ctx
              }
          )
        )
      )
    },

    releaseIOIgnoreUntrackedFiles := true
  )
