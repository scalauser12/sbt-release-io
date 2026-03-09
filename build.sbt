val Sbt1Version = "1.12.3"
val Sbt2Version = "2.0.0-RC9"
val Scala212    = "2.12.21"
val Scala3      = "3.8.1"

ThisBuild / versionScheme := Some("early-semver")

lazy val commonSettings = Seq(
  organization                  := "io.github.scalauser12",
  crossScalaVersions            := Seq(Scala212, Scala3),
  pluginCrossBuild / sbtVersion := {
    scalaBinaryVersion.value match {
      case "2.12" => Sbt1Version
      case "3"    => Sbt2Version
      case other  =>
        sys.error(
          s"Unsupported Scala binary version for sbt plugin cross-build: $other"
        )
    }
  },
  homepage                      := Some(url("https://github.com/scalauser12/sbt-release-io")),
  licenses                      := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
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
    "org.typelevel" %% "cats-effect"                % "3.6.3",
    "org.specs2"    %% "specs2-core"                % "4.23.0" % Test,
    "org.typelevel" %% "cats-effect-testing-specs2" % "1.7.0"  % Test
  ),
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked"
  ),
  scriptedLaunchOpts            := {
    scriptedLaunchOpts.value ++
      Seq("-Xmx1024M", "-Dplugin.version=" + version.value) ++
      sys.props.get("sbt.version").map(v => s"-Dsbt.version=$v").toSeq
  },
  scriptedBufferLog             := true,
  scriptedParallelInstances     := 4
)

lazy val core = (project in file("modules/core"))
  .enablePlugins(SbtPlugin)
  .settings(
    commonSettings,
    name        := "sbt-release-io",
    description := "An sbt plugin wrapping sbt-release with cats-effect IO",
    Test / unmanagedSourceDirectories += baseDirectory.value / "examples",
    addSbtPlugin("com.github.sbt" % "sbt-release" % "1.4.0")
  )

lazy val monorepo = (project in file("modules/monorepo"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core, core % "test->test")
  .settings(
    commonSettings,
    name        := "sbt-release-io-monorepo",
    description := "Monorepo extension for sbt-release-io",
    Test / unmanagedSourceDirectories += baseDirectory.value / "examples"
  )

lazy val root = (project in file("."))
  .aggregate(core, monorepo)
  .settings(
    name           := "sbt-release-io-root",
    publish / skip := true
  )

Global / excludeLintKeys ++= Set(
  ThisBuild / git.gitUncommittedChanges,
  core / git.gitDescribedVersion,
  monorepo / git.gitDescribedVersion,
  root / git.gitDescribedVersion
)
