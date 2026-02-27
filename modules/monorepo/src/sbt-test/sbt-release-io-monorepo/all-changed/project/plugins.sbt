sys.props.get("plugin.version") match {
  case Some(ver) =>
    addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % ver)
  case _         => sys.error("Plugin version not set")
}
