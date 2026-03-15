package io.release.version

import java.util.regex.Pattern
import scala.util.control.Exception.allCatch
import scala.util.matching.Regex

object Version {
  sealed trait Bump {
    def bump: Version => Version
  }

  object Bump {

    /** Bump the major version. Ex. 1.0.0 -> 2.0.0 */
    case object Major extends Bump { def bump: Version => Version = _.bumpMajor }

    /** Bump the minor version. Ex. 1.0.0 -> 1.1.0 */
    case object Minor extends Bump { def bump: Version => Version = _.bumpMinor }

    /** Bump the bugfix version. Ex. 1.0.0 -> 1.0.1 */
    case object Bugfix extends Bump { def bump: Version => Version = _.bumpBugfix }

    /** Bump the nano version. Ex. 1.0.0.0 -> 1.0.0.1 */
    case object Nano extends Bump { def bump: Version => Version = _.bumpNano }

    /** Increment the next version component, including prerelease versions.
      * Ex: 1.0-RC1 -> 1.0-RC2, 1.0-alpha -> 1.0
      */
    case object Next extends Bump { def bump: Version => Version = _.bumpNext }

    /** Increment the next version component, removing prerelease qualifiers.
      * Ex: 1.0-RC1 -> 1.0, 1.0.0 -> 1.0.1
      */
    case object NextStable extends Bump { def bump: Version => Version = _.bumpNextStable }

    val default: Bump = Next
  }

  val VersionR: Regex             = """([0-9]+)((?:\.[0-9]+)+)?([\.\-0-9a-zA-Z]*)?""".r
  val PreReleaseQualifierR: Regex =
    """[\.-](?i:rc|m|alpha|beta)[\.-]?[0-9]*""".r

  def apply(s: String): Option[Version] =
    allCatch opt {
      val VersionR(maj, subs, qual) = s: @unchecked
      val subSeq: Seq[Int]          = Option(subs)
        .map(_.split('.').filterNot(_.trim.isEmpty).map(_.toInt).toSeq)
        .getOrElse(Nil)
      Version(maj.toInt, subSeq, Option(qual).filterNot(_.isEmpty))
    }
}

case class Version(major: Int, subversions: Seq[Int], qualifier: Option[String]) {

  def bumpNext: Version = {
    val bumpedPrereleaseOpt =
      qualifier.collect { case rawQualifier @ Version.PreReleaseQualifierR() =>
        val qualifierEndsWithNumberR = """[0-9]*$""".r

        val opt = for {
          versionNumberStr <- qualifierEndsWithNumberR.findFirstIn(rawQualifier).filter(_.nonEmpty)
          versionNumber    <- allCatch.opt(versionNumberStr.toInt)
          newVersionNumber  = versionNumber + 1
          newQualifier      =
            rawQualifier.replaceFirst(Pattern.quote(versionNumberStr), newVersionNumber.toString)
        } yield Version(major, subversions, Some(newQualifier))

        opt.getOrElse(this.withoutQualifier)
      }

    bumpNextGeneric(bumpedPrereleaseOpt)
  }

  def bumpNextStable: Version = {
    val bumpedPrereleaseOpt =
      qualifier.collect { case Version.PreReleaseQualifierR() =>
        withoutQualifier
      }
    bumpNextGeneric(bumpedPrereleaseOpt)
  }

  private def bumpNextGeneric(bumpedPrereleaseOpt: Option[Version]): Version = {
    def maybeBumpedLastSubversion = bumpSubversionOpt(subversions.length - 1)
    def bumpedMajor               = copy(major = major + 1)
    bumpedPrereleaseOpt.orElse(maybeBumpedLastSubversion).getOrElse(bumpedMajor)
  }

  def bumpMajor: Version  = copy(major = major + 1, subversions = Seq.fill(subversions.length)(0))
  def bumpMinor: Version  = maybeBumpSubversion(0)
  def bumpBugfix: Version = maybeBumpSubversion(1)
  def bumpNano: Version   = maybeBumpSubversion(2)

  def maybeBumpSubversion(idx: Int): Version =
    bumpSubversionOpt(idx).getOrElse(this)

  private def bumpSubversionOpt(idx: Int): Option[Version] = {
    val bumped = subversions.drop(idx)
    val reset  = bumped.drop(1).length
    bumped.headOption.map { head =>
      val patch = (head + 1) +: Seq.fill(reset)(0)
      copy(subversions = subversions.patch(idx, patch, patch.length))
    }
  }

  def bump(bumpType: Version.Bump): Version = bumpType.bump(this)

  def withoutQualifier: Version = copy(qualifier = None)

  def asSnapshot: Version =
    if (isSnapshot) this
    else copy(qualifier = qualifier.map(q => s"$q-SNAPSHOT").orElse(Some("-SNAPSHOT")))

  def isSnapshot: Boolean = qualifier.exists(_.matches("""(^.*)-SNAPSHOT$"""))

  def withoutSnapshot: Version = copy(qualifier = qualifier.flatMap { q =>
    val stripped = """-SNAPSHOT""".r.replaceFirstIn(q, "")
    if (stripped == q) None
    else Option(stripped).filter(_.nonEmpty)
  })

  def render: String = "" + major + subversions.map("." + _).mkString + qualifier.getOrElse("")
}
