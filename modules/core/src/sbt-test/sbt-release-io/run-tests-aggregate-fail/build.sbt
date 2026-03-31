import sbt.IO

lazy val root = (project in file("."))
  .aggregate(sub)
  .settings(
    name                                   := "run-tests-aggregate-fail-test",
    scalaVersion                           := "2.12.18",
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )
lazy val sub  = (project in file("sub"))
  .settings(
    name                                   := "run-tests-aggregate-fail-sub",
    scalaVersion                           := "2.12.18",
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )

releaseIOIgnoreUntrackedFiles := true
releaseIOEnablePublish        := false
releaseIOEnablePush           := false
