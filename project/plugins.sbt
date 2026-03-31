if (!sys.props.get("sbt.version").exists(_.startsWith("2.")))
  addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")
else
  libraryDependencies ++= Nil
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")
addSbtPlugin("org.scoverage"  % "sbt-scoverage"  % "2.4.2")
