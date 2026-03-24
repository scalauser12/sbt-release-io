package io.release.internal

import munit.FunSuite

class ReleaseCliSpec extends FunSuite {

  test("splitMode - treat help as a first-token subcommand") {
    val (mode, remaining) = ReleaseCli.splitMode(Seq("help", "ignored"))

    assertEquals(mode, ReleaseCli.CommandMode.Help)
    assertEquals(remaining, Seq("ignored"))
  }

  test("splitMode - treat check as a first-token subcommand") {
    val (mode, remaining) =
      ReleaseCli.splitMode(Seq("check", "with-defaults", "release-version", "1.0.0"))

    assertEquals(mode, ReleaseCli.CommandMode.Check)
    assertEquals(remaining, Seq("with-defaults", "release-version", "1.0.0"))
  }

  test("parse - reject trailing tokens after help") {
    val result = ReleaseCli.parse(Seq("help", "extra"), "releaseIO")

    assertEquals(
      result,
      Left("Unexpected arguments after 'help'. See 'releaseIO help' for usage.")
    )
  }

  test("parse - parse check flags and overrides after the subcommand") {
    val result = ReleaseCli.parse(
      Seq("check", "with-defaults", "release-version", "1.0.0", "next-version", "1.1.0-SNAPSHOT"),
      "releaseIO"
    )

    assertEquals(
      result,
      Right(
        ReleaseCli.Parsed(
          ReleaseCli.CommandMode.Check,
          Seq(
            ReleaseCli.Arg.WithDefaults,
            ReleaseCli.Arg.ReleaseVersion("1.0.0"),
            ReleaseCli.Arg.NextVersion("1.1.0-SNAPSHOT")
          )
        )
      )
    )
  }

  test("parse - default to run mode when no subcommand is provided") {
    val result = ReleaseCli.parse(
      Seq("with-defaults", "release-version", "1.0.0"),
      "releaseIO"
    )

    assertEquals(
      result,
      Right(
        ReleaseCli.Parsed(
          ReleaseCli.CommandMode.Run,
          Seq(
            ReleaseCli.Arg.WithDefaults,
            ReleaseCli.Arg.ReleaseVersion("1.0.0")
          )
        )
      )
    )
  }

  test("parse - fail with a help hint when a value is missing") {
    val result = ReleaseCli.parse(Seq("check", "release-version"), "releaseIO")

    assertEquals(
      result,
      Left("Missing value after 'release-version'. See 'releaseIO help' for usage.")
    )
  }
}
