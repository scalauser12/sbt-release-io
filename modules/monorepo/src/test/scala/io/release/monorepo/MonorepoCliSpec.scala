package io.release.monorepo

import io.release.internal.PluginEntrypointSupport.CommandMode
import munit.FunSuite

class MonorepoCliSpec extends FunSuite {

  test("splitMode - reserve help only in the first token position") {
    val (mode, remaining) = MonorepoCli.splitMode(Seq("help", "core"))

    assertEquals(mode, CommandMode.Help)
    assertEquals(remaining, Seq("core"))
  }

  test("parse - decode later help tokens as project selections after parser admission") {
    val result = MonorepoCli.parse(Seq("core", "help"), "releaseIOMonorepo")

    assertEquals(
      result,
      Right(
        MonorepoCli.Parsed(
          CommandMode.Run,
          Seq(
            MonorepoCli.Arg.SelectProject("core"),
            MonorepoCli.Arg.SelectProject("help")
          )
        )
      )
    )
  }

  test("parse - decode explicit project selector tokens after parser admission") {
    val result = MonorepoCli.parse(
      Seq("project", "cross", "with-defaults"),
      "releaseIOMonorepo"
    )

    assertEquals(
      result,
      Right(
        MonorepoCli.Parsed(
          CommandMode.Run,
          Seq(
            MonorepoCli.Arg.SelectProject("cross"),
            MonorepoCli.Arg.WithDefaults
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
          CommandMode.Check,
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

  test("parse - decode typed monorepo decision defaults") {
    val result = MonorepoCli.parse(
      Seq(
        "default-tag-exists-answer",
        "k",
        "default-snapshot-dependencies-answer",
        "y",
        "default-push-answer",
        "n"
      ),
      "releaseIOMonorepo"
    )

    assertEquals(
      result,
      Right(
        MonorepoCli.Parsed(
          CommandMode.Run,
          Seq(
            MonorepoCli.Arg.TagDefault("k"),
            MonorepoCli.Arg.SnapshotDependenciesDefault(true),
            MonorepoCli.Arg.PushDefault(false)
          )
        )
      )
    )
  }

  test("parse - reject invalid typed decision defaults with a usage hint") {
    val result = MonorepoCli.parse(
      Seq("default-upstream-behind-answer", "later"),
      "releaseIOMonorepo"
    )

    assertEquals(
      result,
      Left(
        "Invalid value 'later' for 'default-upstream-behind-answer'. Expected 'y' or 'n'. " +
          "See 'releaseIOMonorepo help' for usage."
      )
    )
  }

  test("parse - reject override values without a project prefix") {
    val result = MonorepoCli.parse(
      Seq("check", "with-defaults", "release-version", "1.0.0"),
      "releaseIOMonorepo"
    )

    assertEquals(
      result,
      Left(
        "Invalid release-version format. Expected project=version. " +
          "See 'releaseIOMonorepo help' for usage."
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

  test("parse - fail with a help hint when the explicit selector is missing its project id") {
    val result = MonorepoCli.parse(Seq("project"), "releaseIOMonorepo")

    assertEquals(
      result,
      Left("Missing value after 'project'. See 'releaseIOMonorepo help' for usage.")
    )
  }
}
