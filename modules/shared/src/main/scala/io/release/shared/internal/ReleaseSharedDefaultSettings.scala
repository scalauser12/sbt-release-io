package io.release.shared.internal

import io.release.ReleaseSharedDefaultSettingsSupport
import sbt.*

private[release] object ReleaseSharedDefaultSettings {

  lazy val pluginDefaultSettings: Seq[Setting[?]] =
    ReleaseSharedDefaultSettingsSupport.pluginDefaultSettings

  lazy val buildDefaultSettings: Seq[Setting[?]] =
    ReleaseSharedDefaultSettingsSupport.buildDefaultSettings
}
