import java.net.URI

import sbt.*
import sbt.Keys.*

object BuildSettingsCompat:

  lazy val apache2LicenseSetting =
    licenses := List(
      sbt.librarymanagement.License(
        "Apache-2.0",
        new URI("https://www.apache.org/licenses/LICENSE-2.0")
      )
    )
