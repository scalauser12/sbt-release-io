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
    name := "custom-version-format-test",

    // Point version files to .properties files instead of version.sbt
    releaseIOMonorepoVersionFile := {
      val build = loadedBuild.value
      (ref: ProjectRef, _: State) => {
        val projBase = build.allProjectRefs
          .find(_._1 == ref)
          .map(_._2.base)
          .getOrElse(sys.error(s"Project ${ref.project} not found in build"))
        projBase / "version.properties"
      }
    },

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

    // Custom writer: read existing file, replace only the app.version line, preserve everything else
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

    releaseIgnoreUntrackedFiles := true,

    checkAll := {
      // Check core version.properties has next version and preserved app.name
      val coreProps = IO.read(file("core/version.properties"))
      assert(
        coreProps.contains("app.version=0.2.0-SNAPSHOT"),
        s"Expected core version.properties to contain 'app.version=0.2.0-SNAPSHOT' but got:\n$coreProps"
      )
      assert(
        coreProps.contains("app.name=core-service"),
        s"Expected core version.properties to preserve 'app.name=core-service' but got:\n$coreProps"
      )
      assert(
        !coreProps.contains("version :="),
        s"Expected core version.properties to NOT contain sbt format but got:\n$coreProps"
      )

      // Check api version.properties has next version and preserved app.name
      val apiProps = IO.read(file("api/version.properties"))
      assert(
        apiProps.contains("app.version=0.2.0-SNAPSHOT"),
        s"Expected api version.properties to contain 'app.version=0.2.0-SNAPSHOT' but got:\n$apiProps"
      )
      assert(
        apiProps.contains("app.name=api-service"),
        s"Expected api version.properties to preserve 'app.name=api-service' but got:\n$apiProps"
      )

      // Check git tag commit has release version in properties format
      val coreTagContent = "git show core/v0.1.0:core/version.properties".!!
      assert(
        coreTagContent.contains("app.version=0.1.0"),
        s"Expected tagged core to contain 'app.version=0.1.0' but got:\n$coreTagContent"
      )
      assert(
        !coreTagContent.contains("-SNAPSHOT"),
        s"Expected tagged core to NOT contain '-SNAPSHOT' but got:\n$coreTagContent"
      )
      assert(
        coreTagContent.contains("app.name=core-service"),
        s"Expected tagged core to preserve 'app.name=core-service' but got:\n$coreTagContent"
      )

      val apiTagContent = "git show api/v0.1.0:api/version.properties".!!
      assert(
        apiTagContent.contains("app.version=0.1.0"),
        s"Expected tagged api to contain 'app.version=0.1.0' but got:\n$apiTagContent"
      )
      assert(
        !apiTagContent.contains("-SNAPSHOT"),
        s"Expected tagged api to NOT contain '-SNAPSHOT' but got:\n$apiTagContent"
      )
      assert(
        apiTagContent.contains("app.name=api-service"),
        s"Expected tagged api to preserve 'app.name=api-service' but got:\n$apiTagContent"
      )
    }
  )
