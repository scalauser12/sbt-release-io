import scala.sys.process._
import sbtrelease.ReleasePlugin.autoImport.releaseVersionFile

lazy val core = (project in file("core"))
  .settings(
    name               := "core",
    scalaVersion       := "2.12.18",
    releaseVersionFile := baseDirectory.value / "version.properties"
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
    name         := "per-project-releaseversionfile-test",
    scalaVersion := "2.12.18",

    releaseIOMonorepoReadVersion := { (f: File) =>
      _root_.cats.effect.IO.blocking(sbt.IO.read(f)).flatMap { contents =>
        if (f.getName == "version.properties") {
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
        } else {
          contents.linesIterator
            .map(_.trim)
            .collectFirst {
              case line if line.startsWith("""ThisBuild / version := """) =>
                line.stripPrefix("""ThisBuild / version := """).drop(1).takeWhile(_ != '"')
              case line if line.startsWith("""version := """) =>
                line.stripPrefix("""version := """).drop(1).takeWhile(_ != '"')
            } match {
            case Some(version) => _root_.cats.effect.IO.pure(version)
            case None    =>
              _root_.cats.effect.IO.raiseError(
                new RuntimeException(
                  "Could not parse version from " + f.getName + ". Expected format: version := x.y.z"
                )
              )
          }
        }
      }
    },

    releaseIOMonorepoWriteVersion := { (f: File, ver: String) =>
      if (f.getName == "version.properties")
        _root_.cats.effect.IO.blocking(sbt.IO.read(f)).map { contents =>
          contents.linesIterator
            .map {
              case line if line.startsWith("app.version=") => s"app.version=$ver"
              case line                                    => line
            }
            .mkString("\n") + "\n"
        }
      else
        _root_.cats.effect.IO.pure(s"""version := "$ver"\n""")
    },

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts" ||
      step.name == "run-clean" || step.name == "run-tests"
    },

    releaseIgnoreUntrackedFiles := true,

    checkAll := {
      val coreProps = IO.read(file("core/version.properties"))
      assert(
        coreProps.contains("app.version=0.1.1-SNAPSHOT"),
        s"Expected core version.properties to contain 'app.version=0.1.1-SNAPSHOT' but got:\n$coreProps"
      )
      assert(
        coreProps.contains("app.name=core-service"),
        s"Expected core version.properties to preserve 'app.name=core-service' but got:\n$coreProps"
      )

      val apiVersion = IO.read(file("api/version.sbt"))
      assert(
        apiVersion.contains("""version := "0.1.1-SNAPSHOT""""),
        s"Expected api/version.sbt to contain 0.1.1-SNAPSHOT but got:\n$apiVersion"
      )

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

      val apiTagContent = "git show api/v0.1.0:api/version.sbt".!!
      assert(
        apiTagContent.contains("""version := "0.1.0""""),
        s"Expected tagged api to contain version 0.1.0 but got:\n$apiTagContent"
      )
      assert(
        !apiTagContent.contains("-SNAPSHOT"),
        s"Expected tagged api to NOT contain '-SNAPSHOT' but got:\n$apiTagContent"
      )

      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("api/v0.1.0", "core/v0.1.0"),
        s"Expected tags [api/v0.1.0, core/v0.1.0] but got [${tags.mkString(", ")}]"
      )
    }
  )
