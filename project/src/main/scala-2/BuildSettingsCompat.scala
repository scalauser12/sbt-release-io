import java.net.URI

import sbt._
import sbt.Keys._

object BuildSettingsCompat {

  lazy val apache2LicenseSetting =
    licenses := List(
      "Apache-2.0" -> new URI("https://www.apache.org/licenses/LICENSE-2.0").toURL
    )
}
