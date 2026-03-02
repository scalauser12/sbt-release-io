import scala.sys.process._

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

val checkAll = taskKey[Unit]("Run all verification checks")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                            := "custom-detector-uses-basedir-test",
    // Custom change detector: uses baseDir to diff against HEAD~1, excluding version.sbt
    releaseIOMonorepoChangeDetector := Some { (_: ProjectRef, baseDir: File, _: State) =>
      _root_.cats.effect.IO.blocking {
        val output = Process(
          Seq("git", "diff", "--name-only", "HEAD~1", "--", baseDir.getAbsolutePath),
          baseDirectory.value
        ).!!.trim
        output.linesIterator
          .filter(_.nonEmpty)
          .filterNot(_.endsWith("version.sbt"))
          .nonEmpty
      }
    },
    releaseIOMonorepoProcess        := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },
    releaseIgnoreUntrackedFiles     := true,
    checkAll                        := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(
        tags.length == 1,
        s"Expected 1 tag (core only) but found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(tags.head == "core/v1.0.0", s"Expected tag 'core/v1.0.0' but got '${tags.head}'")

      val coreContents = IO.read(file("core/version.sbt"))
      assert(
        coreContents.contains("1.1.0-SNAPSHOT"),
        s"Expected core version 1.1.0-SNAPSHOT but got: $coreContents"
      )

      val apiContents = IO.read(file("api/version.sbt"))
      assert(
        apiContents.contains("0.1.0-SNAPSHOT"),
        s"Expected api version 0.1.0-SNAPSHOT (unchanged) but got: $apiContents"
      )
    }
  )
