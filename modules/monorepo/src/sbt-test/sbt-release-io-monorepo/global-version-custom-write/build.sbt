import scala.sys.process.*

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
    name := "global-version-custom-write-test",

    releaseIOMonorepoUseGlobalVersion := true,

    // In global mode, resolveVersionFile uses releaseIOVersionFile
    releaseIOVersionFile := file("version.properties"),

    // Custom reader: parse app.version=x.y.z from properties file
    releaseIOMonorepoReadVersion := { (f: File) =>
      _root_.cats.effect.IO.blocking(sbt.IO.read(f)).flatMap { contents =>
        val pattern = """app\.version=(.+)""".r
        pattern.findFirstMatchIn(contents) match {
          case Some(m) => _root_.cats.effect.IO.pure(m.group(1).trim)
          case None    =>
            _root_.cats.effect.IO.raiseError(
              new RuntimeException(
                s"Could not parse version from ${f.getName}. Expected format: app.version=x.y.z"
              )
            )
        }
      }
    },

    // Custom writer: replace only the app.version line, preserve everything else
    releaseIOMonorepoWriteVersion := { (f: File, ver: String) =>
      _root_.cats.effect.IO.blocking(sbt.IO.read(f)).map { contents =>
        contents.linesIterator
          .map {
            case line if line.startsWith("app.version=") => s"app.version=$ver"
            case line                                    => line
          }
          .mkString("\n") + "\n"
      }
    },

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkAll := {
      // Check version.properties has next version and preserved app.name
      val props = IO.read(file("version.properties"))
      assert(
        props.contains("app.version=1.1.0-SNAPSHOT"),
        s"Expected version.properties to contain 'app.version=1.1.0-SNAPSHOT' but got:\n$props"
      )
      assert(
        props.contains("app.name=monorepo"),
        s"Expected version.properties to preserve 'app.name=monorepo' but got:\n$props"
      )

      // Check git tag commit has release version in properties format
      val tagContent = "git show core/v1.0.0:version.properties".!!
      assert(
        tagContent.contains("app.version=1.0.0"),
        s"Expected tagged commit to contain 'app.version=1.0.0' but got:\n$tagContent"
      )
      assert(
        !tagContent.contains("-SNAPSHOT"),
        s"Expected tagged commit to NOT contain '-SNAPSHOT' but got:\n$tagContent"
      )
      assert(
        tagContent.contains("app.name=monorepo"),
        s"Expected tagged commit to preserve 'app.name=monorepo' but got:\n$tagContent"
      )

      // Check both per-project tags exist
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty)
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.sorted.toList == List("api/v1.0.0", "core/v1.0.0"),
        s"Expected tags [api/v1.0.0, core/v1.0.0] but got [${tags.sorted.mkString(", ")}]"
      )
    }
  )
