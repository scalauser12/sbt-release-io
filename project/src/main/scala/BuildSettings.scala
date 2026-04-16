import sbt._
import sbt.Keys._
import sbt.ScriptedPlugin.autoImport._

object BuildSettings {

  def forwardedScriptedJvmArgs: Seq[String] =
    Seq(
      "sbt.ivy.home",
      "sbt.boot.directory",
      "sbt.global.base",
      "sbt.repository.config",
      "sbt.override.build.repos"
    ).flatMap(key => sys.props.get(key).map(value => s"-D$key=$value"))

  def scriptedModuleSettings(localDependency: String) = Seq(
    scriptedDependencies := scriptedDependencies
      .dependsOn(LocalProject(localDependency) / publishLocal)
      .value,
    Test / unmanagedSourceDirectories += baseDirectory.value / "examples"
  )

  lazy val commonSettings = Seq(
    organization                  := "io.github.scalauser12",
    crossScalaVersions            := Seq(BuildVersions.scala212, BuildVersions.scala3),
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" => BuildVersions.sbt1Version
        case "3"    => BuildVersions.sbt2Version
        case other  =>
          sys.error(
            s"Unsupported Scala binary version for sbt plugin cross-build: $other"
          )
      }
    },
    homepage                      := Some(url("https://github.com/scalauser12/sbt-release-io")),
    BuildSettingsCompat.apache2LicenseSetting,
    developers                    := List(
      Developer(
        id = "scalauser12",
        name = "Boris Kotsev",
        email = "scalauser12@users.noreply.github.com",
        url = url("https://github.com/scalauser12")
      )
    ),
    scmInfo                       := Some(
      ScmInfo(
        url("https://github.com/scalauser12/sbt-release-io"),
        "scm:git:git@github.com:scalauser12/sbt-release-io.git"
      )
    ),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"       % BuildVersions.catsEffectVersion,
      "org.scalameta" %% "munit"             % BuildVersions.munitVersion           % Test,
      "org.typelevel" %% "munit-cats-effect" % BuildVersions.munitCatsEffectVersion % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    ),
    // Scala 3 `-Wunused:imports` spams false positives on `import sbt.{internal as _, *}`.
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 12)) => Seq("-Ywarn-unused-import")
        case _             => Nil
      }
    },
    scriptedLaunchOpts            := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value) ++
        sys.props.get("sbt.version").map(v => s"-Dsbt.version=$v").toSeq ++
        forwardedScriptedJvmArgs
    },
    scriptedBufferLog             := true,
    scriptedParallelInstances     := 4,
    Test / fork                   := true,
    Test / parallelExecution      := false,
    testFrameworks += new TestFramework("munit.Framework")
  )
}
