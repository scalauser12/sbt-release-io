package io.release.version

/** Simple semantic version representation with parsing and bumping. */
case class Version(major: Int, minor: Int, patch: Int, qualifier: Option[String] = None) {

  def isSnapshot: Boolean = qualifier.contains("SNAPSHOT")

  def withoutQualifier: Version = copy(qualifier = None)

  def asSnapshot: Version = copy(qualifier = Some("SNAPSHOT"))

  def bumpMajor: Version = Version(major + 1, 0, 0)

  def bumpMinor: Version = Version(major, minor + 1, 0)

  def bumpPatch: Version = Version(major, minor, patch + 1)

  def string: String = {
    val base = s"$major.$minor.$patch"
    qualifier.fold(base)(q => s"$base-$q")
  }

  override def toString: String = string
}

object Version {
  private val Pattern = """(\d+)\.(\d+)\.(\d+)(?:-(.+))?""".r

  def parse(s: String): Option[Version] = s.trim match {
    case Pattern(maj, min, pat, qual) =>
      Some(Version(maj.toInt, min.toInt, pat.toInt, Option(qual)))
    case _ => None
  }

  /** Derive the release version from a snapshot version. */
  def releaseVersion(v: Version): Version = v.withoutQualifier

  /** Derive the next development version by bumping patch and adding -SNAPSHOT. */
  def nextVersion(v: Version): Version = v.withoutQualifier.bumpPatch.asSnapshot
}
