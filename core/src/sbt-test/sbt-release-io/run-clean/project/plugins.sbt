sys.props.get("plugin.version") match {
  case Some(ver) => addSbtPlugin("io.github.sbt-release-io" % "sbt-release-io" % ver)
  case None      => sys.error("plugin.version system property is not set")
}
