sys.props.get("plugin.version") match {
  case Some(ver) => addSbtPlugin("io.github.sbt-release-io" % "sbt-release-io" % ver)
  case _ => sys.error("Plugin version not set")
}
