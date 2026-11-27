import sbt.IO

lazy val root = (project in file("."))
  .aggregate(sub)
  .settings(
    name                                   := "run-tests-aggregate-fail-test",
    scalaVersion                           := "2.12.21",
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.6" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )
lazy val sub  = (project in file("sub"))
  .settings(
    name                                   := "run-tests-aggregate-fail-sub",
    scalaVersion                           := "2.12.21",
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.6" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )

releaseIOVcsIgnoreUntrackedFiles := true
releaseIOPolicyEnablePublish     := false
releaseIOPolicyEnablePush        := false
