import java.io.FileInputStream
import java.util.Properties

import sbt.IO
import sbt.file

object BuildVersions {

  def readVersionFile(path: String): String =
    IO.read(file(path)).trim

  def readSbt1Version(path: String): String = {
    val properties = new Properties()
    val input      = new FileInputStream(file(path))
    try properties.load(input)
    finally input.close()

    Option(properties.getProperty("sbt.version"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(sys.error(s"Missing sbt.version entry in $path"))
  }

  val sbt1Version            = readSbt1Version("project/build.properties")
  val sbt2Version            = readVersionFile("project/sbt2.version")
  val scala212               = "2.12.21"
  val scala3                 = "3.8.1"
  val catsEffectVersion      = "3.7.0"
  val munitVersion           = "1.2.4"
  val munitCatsEffectVersion = "2.2.0"
  val scalametaParsersVersion = "4.13.8"
}
