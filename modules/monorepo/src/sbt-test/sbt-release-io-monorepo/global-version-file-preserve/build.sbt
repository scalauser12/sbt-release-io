import scala.sys.process.*

// Use a custom VERSION file instead of the default version.sbt
val versionFile = file("VERSION")
ThisBuild / version := IO.read(versionFile).trim

// Point releaseIOVersionFile to the custom file
releaseIOVersionFile := versionFile

releaseIOReadVersion := { (f: java.io.File) =>
  _root_.cats.effect.IO.blocking(IO.read(f).trim)
}

releaseIOVersionFileContents := { (_: java.io.File, ver: String) =>
  _root_.cats.effect.IO.pure(ver + "\n")
}

lazy val core = (project in file("core"))
  .settings(
    name         := "core",
    scalaVersion := "2.12.18"
  )

lazy val api = (project in file("api"))
  .settings(
    name         := "api",
    scalaVersion := "2.12.18"
  )

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "global-version-file-preserve-test",

    releaseIOMonorepoUseGlobalVersion := true,

    releaseIOMonorepoReadVersion := { (f: java.io.File) =>
      _root_.cats.effect.IO.blocking(IO.read(f).trim)
    },

    releaseIOMonorepoVersionFileContents := { (_: java.io.File, ver: String) =>
      _root_.cats.effect.IO.pure(ver + "\n")
    },

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    },

    releaseIOIgnoreUntrackedFiles := true,

    checkAll := {
      // The VERSION file (not version.sbt) should contain the next snapshot
      val contents = IO.read(file("VERSION")).trim
      assert(
        contents == "2.1.0-SNAPSHOT",
        s"Expected VERSION file to contain 2.1.0-SNAPSHOT but got: $contents"
      )
      // version.sbt should NOT exist (we never use it)
      assert(
        !file("version.sbt").exists(),
        "version.sbt should not exist — release should use the custom VERSION file"
      )
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      assert(tags.length == 2, s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}")
      assert(
        tags.toList == List("api/v2.0.0", "core/v2.0.0"),
        s"Expected [api/v2.0.0, core/v2.0.0] but got [${tags.mkString(", ")}]"
      )
    }
  )

val checkAll = taskKey[Unit]("Check VERSION file and tags")
