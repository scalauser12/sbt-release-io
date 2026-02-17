package io.release.version

import sbtrelease.{Version => SbtVersion}

/**
 * Facade for sbt-release's Version class.
 * Delegates all version parsing and transformation logic to the upstream library.
 */
object Version {

  /** Parse a version string using sbt-release's parser. */
  def parse(s: String): Option[SbtVersion] = SbtVersion(s)

  /** Derive the release version by removing qualifiers (e.g., "1.2.3-SNAPSHOT" → "1.2.3"). */
  def releaseVersion(v: SbtVersion): String = v.withoutQualifier.unapply

  /**
   * Derive the next development version by bumping patch and adding -SNAPSHOT.
   * Uses sbt-release's default bump strategy (Bugfix/Patch).
   */
  def nextVersion(v: SbtVersion): String =
    v.bump(SbtVersion.Bump.Bugfix).asSnapshot.unapply
}
