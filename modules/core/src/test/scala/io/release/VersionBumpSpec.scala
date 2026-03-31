package io.release

import io.release.version.Version
import munit.FunSuite

class VersionBumpSpec extends FunSuite {

  private def version(v: String): Version = Version(v).getOrElse(sys.error("Can't parse " + v))

  test("Bump.Major.bump - bumps the major version and resets minor and bugfix") {
    assertEquals(Version.Bump.Major.bump(version("1.2.3")).render, "2.0.0")
  }

  test("Bump.Minor.bump - bumps the minor version and resets bugfix") {
    assertEquals(Version.Bump.Minor.bump(version("1.2.3")).render, "1.3.0")
  }

  test("Bump.Bugfix.bump - bumps the bugfix version") {
    assertEquals(Version.Bump.Bugfix.bump(version("1.2.3")).render, "1.2.4")
  }

  test("Bump.Nano.bump - bumps the nano version") {
    assertEquals(Version.Bump.Nano.bump(version("1.2.3.4")).render, "1.2.3.5")
  }

  test("Bump.Next.bump - increments the prerelease qualifier number") {
    assertEquals(Version.Bump.Next.bump(version("1.2.3-RC1")).render, "1.2.3-RC2")
  }

  test("Bump.NextStable.bump - removes the prerelease qualifier") {
    assertEquals(Version.Bump.NextStable.bump(version("1.2.3-RC1")).render, "1.2.3")
  }

  test("Bump.default - equals Bump.Next") {
    assertEquals(Version.Bump.default, Version.Bump.Next)
  }
}
