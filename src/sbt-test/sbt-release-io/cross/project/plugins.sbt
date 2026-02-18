sys.props.get("plugin.version") match {
  case Some(version) => addSbtPlugin("io.github.sbt-release-io" % "sbt-release-io" % version)
  case _ => sys.error("Plugin version not specified. Set via -Dplugin.version=...")
}
