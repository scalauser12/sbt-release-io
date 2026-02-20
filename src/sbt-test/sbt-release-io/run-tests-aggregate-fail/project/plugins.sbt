sys.props.get("plugin.version") match {
  case Some(pluginVersion) => addSbtPlugin("io.github.sbt-release-io" % "sbt-release-io" % pluginVersion)
  case _ => sys.error("""|The system property 'plugin.version' is not defined.
                         |Specify this property using the scriptedLaunchOpts -D.""".stripMargin)
}
