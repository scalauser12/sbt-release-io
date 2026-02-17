import sbt.Keys._

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-release-io",
    organization := "io.github.sbt-release-io",
    version := "0.1.0-SNAPSHOT",
    sbtPlugin := true,
    scalaVersion := "2.12.19",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.5.4"
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    )
  )
