package io.release.internal

import munit.FunSuite

class ReleaseCliSpec extends FunSuite {

  import ReleaseCli.CommandMode

  test("splitMode - treat help as a first-token subcommand") {
    val (mode, remaining) = ReleaseCli.splitMode(Seq("help", "ignored"))

    assertEquals(mode, CommandMode.Help)
    assertEquals(remaining, Seq("ignored"))
  }

  test("splitMode - treat check as a first-token subcommand") {
    val (mode, remaining) =
      ReleaseCli.splitMode(Seq("check", "with-defaults", "release-version", "1.0.0"))

    assertEquals(mode, CommandMode.Check)
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
          CommandMode.Check,
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
          CommandMode.Run,
          Seq(
            ReleaseCli.Arg.WithDefaults,
            ReleaseCli.Arg.ReleaseVersion("1.0.0")
          )
        )
      )
    )
  }

  test("parse - decode typed decision defaults") {
    val result = ReleaseCli.parse(
      Seq(
        "default-snapshot-dependencies-answer",
        "y",
        "default-remote-check-failure-answer",
        "n",
        "default-upstream-behind-answer",
        "y",
        "default-push-answer",
        "n"
      ),
      "releaseIO"
    )

    assertEquals(
      result,
      Right(
        ReleaseCli.Parsed(
          CommandMode.Run,
          Seq(
            ReleaseCli.Arg.SnapshotDependenciesDefault(true),
            ReleaseCli.Arg.RemoteCheckFailureDefault(false),
            ReleaseCli.Arg.UpstreamBehindDefault(true),
            ReleaseCli.Arg.PushDefault(false)
          )
        )
      )
    )
  }

  test("parse - reject invalid typed decision defaults with a usage hint") {
    val result = ReleaseCli.parse(
      Seq("default-push-answer", "maybe"),
      "releaseIO"
    )

    assertEquals(
      result,
      Left(
        "Invalid value 'maybe' for 'default-push-answer'. Expected 'y' or 'n'. " +
          "See 'releaseIO help' for usage."
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
