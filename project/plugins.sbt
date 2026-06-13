// sbt 2 provides native formatting support, so only load sbt-scalafmt on sbt 1.
if (!sys.props.get("sbt.version").exists(_.startsWith("2.")))
  Seq(addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6"))
else Seq.empty[Def.Setting[_]]

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")
