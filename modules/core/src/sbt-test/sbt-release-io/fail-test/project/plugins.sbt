val pluginVersionFile = file("target/plugin-version.txt")

val maybePluginVersion =
  sys.props.get("plugin.version").orElse {
    if (pluginVersionFile.exists()) Some(sbt.IO.read(pluginVersionFile).trim).filter(_.nonEmpty)
    else None
  }

maybePluginVersion match {
  case Some(ver) => addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % ver)
  case _         => sys.error("Plugin version not set")
}
