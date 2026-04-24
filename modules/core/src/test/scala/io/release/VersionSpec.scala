package io.release

import io.release.version.Version
import munit.FunSuite

class VersionSpec extends FunSuite {

  private def version(v: String): Version = Version(v) match {
    case Some(parsed) => parsed
    case None         => fail("Can't parse version " + v)
  }

  private def testBumpNext(input: String, expectedOutput: String): Unit =
    assertEquals(version(input).bumpNext.render, expectedOutput)

  private def testBumpNextStable(input: String, expectedOutput: String): Unit =
    assertEquals(version(input).bumpNextStable.render, expectedOutput)

  private def testBothBumpNextStrategies(input: String, expectedOutput: String): Unit = {
    testBumpNext(input, expectedOutput)
    testBumpNextStable(input, expectedOutput)
  }

  test("Next Version bumping - bump the major version if there's only a major version") {
    testBothBumpNextStrategies("1", "2")
  }

  test("Version.apply - return None for an empty string") {
    assertEquals(Version(""), None)
  }

  test("Version.apply - return None for an invalid version string") {
    assertEquals(Version("not-a-version"), None)
  }

  test("Version.apply - reject malformed numeric/qualifier inputs") {
    Seq("1..2", "1abc", "1-", "1.0.0.", "1.2.3SNAPSHOT").foreach { malformed =>
      assertEquals(Version(malformed), None, s"expected None for '$malformed'")
    }
  }

  test("Version.apply - round-trip rendered representative versions") {
    val versions = Seq(
      version("1"),
      version("1.2.3"),
      version("1.2.3.4"),
      version("1.2.3-RC1"),
      version("1.2.3-beta.2"),
      version("1.2.3-RC1-SNAPSHOT")
    )

    versions.foreach { parsed =>
      assertEquals(Version(parsed.render), Some(parsed))
    }
  }

  test("Next Version bumping - bump the minor version if there's only a minor version") {
    testBothBumpNextStrategies("1.2", "1.3")
  }

  test("Next Version bumping - bump the bugfix version if there's only a bugfix version") {
    testBothBumpNextStrategies("1.2.3", "1.2.4")
  }

  test("Next Version bumping - bump the nano version if there's only a nano version") {
    testBothBumpNextStrategies("1.2.3.4", "1.2.3.5")
  }

  test("Next Version bumping - drop the qualifier if it's a pre release with no version") {
    testBothBumpNextStrategies("1-rc", "1")
    testBothBumpNextStrategies("1.0-rc", "1.0")
    testBothBumpNextStrategies("1.0.0-rc", "1.0.0")
    testBothBumpNextStrategies("1.0.0.0-rc", "1.0.0.0")
    testBothBumpNextStrategies("1-beta", "1")
    testBothBumpNextStrategies("1-alpha", "1")
  }

  test("Next Version bumping - pre release with version number - Next bumps qualifier") {
    testBumpNext("1-rc1", "1-rc2")
    testBumpNext("1.2-rc1", "1.2-rc2")
    testBumpNext("1.2.3-rc1", "1.2.3-rc2")
    testBumpNext("1-RC1", "1-RC2")
    testBumpNext("1-M1", "1-M2")
    testBumpNext("1-rc-1", "1-rc-2")
    testBumpNext("1-rc.1", "1-rc.2")
    testBumpNext("1-beta-1", "1-beta-2")
    testBumpNext("1-beta.1", "1-beta.2")
    testBumpNext("1-rc11", "1-rc12")
    testBumpNext("1-RC11", "1-RC12")
  }

  test("Next Version bumping - pre release with version number - NextStable removes qualifier") {
    testBumpNextStable("1-rc1", "1")
    testBumpNextStable("1.2-rc1", "1.2")
    testBumpNextStable("1.2.3-rc1", "1.2.3")
    testBumpNextStable("1-RC1", "1")
    testBumpNextStable("1-M1", "1")
    testBumpNextStable("1-rc-1", "1")
    testBumpNextStable("1-rc.1", "1")
    testBumpNextStable("1-beta-1", "1")
    testBumpNextStable("1-beta.1", "1")
  }

  test("Next Version bumping - never drop the qualifier if it's a final release - major") {
    testBothBumpNextStrategies("1-Final", "2-Final")
  }

  test("Next Version bumping - never drop the qualifier if it's a final release - minor") {
    testBothBumpNextStrategies("1.2-Final", "1.3-Final")
  }

  test("Next Version bumping - never drop the qualifier if it's a final release - subversion") {
    testBothBumpNextStrategies("1.2.3-Final", "1.2.4-Final")
  }

  test("Next Version bumping - never drop the qualifier if it's a final release - nano") {
    testBothBumpNextStrategies("1.2.3.4-Final", "1.2.3.5-Final")
  }

  test("Major Version bumping - bump the major version and reset other versions") {
    assertEquals(version("1.2.3.4.5").bumpMajor.render, "2.0.0.0.0")
  }

  test("Major Version bumping - not drop the qualifier") {
    assertEquals(version("1.2.3.4.5-alpha").bumpMajor.render, "2.0.0.0.0-alpha")
  }

  test("Minor Version bumping - bump the minor version") {
    assertEquals(version("1.2").bumpMinor.render, "1.3")
  }

  test("Minor Version bumping - bump the minor version and reset other subversions") {
    assertEquals(version("1.2.3.4.5").bumpMinor.render, "1.3.0.0.0")
  }

  test("Minor Version bumping - not bump the major version when no minor version") {
    assertEquals(version("1").bumpMinor.render, "1")
  }

  test("Minor Version bumping - not drop the qualifier") {
    assertEquals(version("1.2.3.4.5-alpha").bumpMinor.render, "1.3.0.0.0-alpha")
  }

  test("Subversion bumping - bump the subversion") {
    assertEquals(version("1.2").maybeBumpSubversion(0).render, "1.3")
  }

  test("Subversion bumping - bump the subversion and reset lower subversions") {
    assertEquals(version("1.2.3.4.5").maybeBumpSubversion(0).render, "1.3.0.0.0")
  }

  test("Subversion bumping - not change anything with an invalid subversion index") {
    assertEquals(version("1.2-beta").maybeBumpSubversion(1).render, "1.2-beta")
  }

  test("Subversion bumping - not drop the qualifier") {
    assertEquals(
      version("1.2.3.4.5-alpha").maybeBumpSubversion(2).render,
      "1.2.3.5.0-alpha"
    )
  }

  test("isSnapshot - return true when -SNAPSHOT is appended with another qualifier") {
    assertEquals(version("1.0.0-RC1-SNAPSHOT").isSnapshot, true)
  }

  test("isSnapshot - return false when -SNAPSHOT is not appended but another qualifier exists") {
    assertEquals(version("1.0.0-RC1").isSnapshot, false)
  }

  test("isSnapshot - return false when neither -SNAPSHOT nor qualifier are appended") {
    assertEquals(version("1.0.0").isSnapshot, false)
  }

  test("asSnapshot - include qualifier if it exists") {
    assertEquals(version("1.0.0-RC1").asSnapshot.render, "1.0.0-RC1-SNAPSHOT")
  }

  test("asSnapshot - have no qualifier if none exists") {
    assertEquals(version("1.0.0").asSnapshot.render, "1.0.0-SNAPSHOT")
  }

  test("asSnapshot - be idempotent when already a snapshot") {
    assertEquals(version("1.0.0-SNAPSHOT").asSnapshot.render, "1.0.0-SNAPSHOT")
  }

  test("asSnapshot - be idempotent when already a snapshot with qualifier") {
    assertEquals(
      version("1.0.0-RC1-SNAPSHOT").asSnapshot.render,
      "1.0.0-RC1-SNAPSHOT"
    )
  }

  test("withoutSnapshot - remove the snapshot normally") {
    assertEquals(version("1.0.0-SNAPSHOT").withoutSnapshot.render, "1.0.0")
  }

  test("withoutSnapshot - set qualifier to None when snapshot is the only qualifier") {
    assertEquals(version("1.0.0-SNAPSHOT").withoutSnapshot.qualifier, None)
  }

  test("withoutSnapshot - remove the snapshot without removing the qualifier") {
    assertEquals(
      version("1.0.0-RC1-SNAPSHOT").withoutSnapshot.render,
      "1.0.0-RC1"
    )
  }
}
