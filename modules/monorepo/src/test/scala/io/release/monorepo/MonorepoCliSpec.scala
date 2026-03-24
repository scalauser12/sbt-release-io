package io.release.monorepo

import munit.FunSuite

class MonorepoCliSpec extends FunSuite {

  test("splitMode - reserve help only in the first token position") {
    val (mode, remaining) = MonorepoCli.splitMode(Seq("help", "core"))

    assertEquals(mode, MonorepoCli.CommandMode.Help)
    assertEquals(remaining, Seq("core"))
  }

  test("parse - decode later help tokens as project selections after parser admission") {
    val result = MonorepoCli.parse(Seq("core", "help"), "releaseIOMonorepo")

    assertEquals(
      result,
      Right(
        MonorepoCli.Parsed(
          MonorepoCli.CommandMode.Run,
          Seq(
            MonorepoCli.Arg.SelectProject("core"),
            MonorepoCli.Arg.SelectProject("help")
          )
        )
      )
    )
  }

  test("parse - reject trailing tokens after help") {
    val result = MonorepoCli.parse(Seq("help", "extra"), "releaseIOMonorepo")

    assertEquals(
      result,
      Left("Unexpected arguments after 'help'. See 'releaseIOMonorepo help' for usage.")
    )
  }

  test("parse - decode check mode with explicit selection and overrides") {
    val result = MonorepoCli.parse(
      Seq(
        "check",
        "core",
        "with-defaults",
        "release-version",
        "core=1.0.0",
        "next-version",
        "core=1.1.0-SNAPSHOT"
      ),
      "releaseIOMonorepo"
    )

    assertEquals(
      result,
      Right(
        MonorepoCli.Parsed(
          MonorepoCli.CommandMode.Check,
          Seq(
            MonorepoCli.Arg.SelectProject("core"),
            MonorepoCli.Arg.WithDefaults,
            MonorepoCli.Arg.ReleaseVersion("core", "1.0.0"),
            MonorepoCli.Arg.NextVersion("core", "1.1.0-SNAPSHOT")
          )
        )
      )
    )
  }

  test("parse - decode global version overrides in check mode") {
    val result = MonorepoCli.parse(
      Seq(
        "check",
        "with-defaults",
        "release-version",
        "1.0.0",
        "next-version",
        "1.1.0-SNAPSHOT"
      ),
      "releaseIOMonorepo"
    )

    assertEquals(
      result,
      Right(
        MonorepoCli.Parsed(
          MonorepoCli.CommandMode.Check,
          Seq(
            MonorepoCli.Arg.WithDefaults,
            MonorepoCli.Arg.GlobalReleaseVersion("1.0.0"),
            MonorepoCli.Arg.GlobalNextVersion("1.1.0-SNAPSHOT")
          )
        )
      )
    )
  }

  test("parse - fail with a help hint when a keyword is missing its value") {
    val result = MonorepoCli.parse(Seq("check", "release-version"), "releaseIOMonorepo")

    assertEquals(
      result,
      Left("Missing value after 'release-version'. See 'releaseIOMonorepo help' for usage.")
    )
  }
}
