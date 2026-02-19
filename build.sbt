import sbt.Keys.*

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name         := "sbt-release-io",
    organization := "io.github.sbt-release-io",
    version      := "0.1.0-SNAPSHOT",
    sbtPlugin    := true,

    // Scripted test configuration
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog  := false,

    addSbtPlugin("com.github.sbt" % "sbt-release" % "1.4.0"),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.6.3",
      "org.specs2"    %% "specs2-core" % "4.20.4" % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    )
  )
