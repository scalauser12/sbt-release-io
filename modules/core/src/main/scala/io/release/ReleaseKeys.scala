package io.release

import sbt.AttributeKey

/** Attribute keys for command-line arguments stored in `sbt.State` during a release.
  *
  * Delegates to the upstream sbt-release `ReleaseKeys` so that IO-native steps and
  * any upstream sbt-release steps sharing the same State can read the same values.
  * Populated by [[ReleasePluginIOLike.doReleaseIO]] before the release process starts.
  */
object ReleaseKeys {
  import sbtrelease.ReleasePlugin.autoImport.ReleaseKeys as UpstreamKeys

  val useDefaults: AttributeKey[Boolean]                      = UpstreamKeys.useDefaults
  val skipTests: AttributeKey[Boolean]                        = UpstreamKeys.skipTests
  val cross: AttributeKey[Boolean]                            = UpstreamKeys.cross
  val commandLineReleaseVersion: AttributeKey[Option[String]] =
    UpstreamKeys.commandLineReleaseVersion
  val commandLineNextVersion: AttributeKey[Option[String]]    = UpstreamKeys.commandLineNextVersion
  val versions: AttributeKey[(String, String)]                = UpstreamKeys.versions
  val tagDefault: AttributeKey[Option[String]]                = UpstreamKeys.tagDefault
}
