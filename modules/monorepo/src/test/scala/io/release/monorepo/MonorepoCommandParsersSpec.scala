package io.release.monorepo

import io.release.monorepo.internal.*

import cats.effect.IO
import cats.effect.Resource
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.Project
import sbt.internal.util.complete.Parser as ParserImpl

import java.io.File

class MonorepoCommandParsersSpec extends CatsEffectSuite {

  test("build - parse explicit project selection from live project ids") {
    fixtureResource.use { fixture =>
      IO {
        val parser = parserFromState(fixture.state)
        val result = ParserImpl.parse(
          " core with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT",
          parser
        )

        assertEquals(
          result,
          Right(
            Seq(
              "core",
              "with-defaults",
              "release-version",
              "core=1.0.0",
              "next-version",
              "core=1.1.0-SNAPSHOT"
            )
          )
        )
      }
    }
  }

  test("build - parse per-project overrides in check mode") {
    fixtureResource.use { fixture =>
      IO {
        val parser = parserFromState(fixture.state)
        val result = ParserImpl.parse(
          " check with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT",
          parser
        )

        assertEquals(
          result,
          Right(
            Seq(
              "check",
              "with-defaults",
              "release-version",
              "core=1.0.0",
              "next-version",
              "core=1.1.0-SNAPSHOT"
            )
          )
        )
      }
    }
  }

  test("build - parse typed decision-default args") {
    fixtureResource.use { fixture =>
      IO {
        val parser = parserFromState(fixture.state)
        val result = ParserImpl.parse(
          " default-tag-exists-answer k default-push-answer n",
          parser
        )

        assertEquals(
          result,
          Right(
            Seq(
              "default-tag-exists-answer",
              "k",
              "default-push-answer",
              "n"
            )
          )
        )
      }
    }
  }

  test("build - parse explicit project selector for an ordinary project id") {
    val parser = MonorepoCommandParsers.build(Seq("core", "api"))
    val result = ParserImpl.parse(" project core with-defaults", parser)

    assertEquals(result, Right(Seq("project", "core", "with-defaults")))
  }

  test("build - parse explicit project selector for keyword-like project ids") {
    val parser = MonorepoCommandParsers.build(Seq("core", "cross", "help"))

    assertEquals(
      ParserImpl.parse(" project cross with-defaults", parser),
      Right(Seq("project", "cross", "with-defaults"))
    )
    assertEquals(
      ParserImpl.parse(" project help with-defaults", parser),
      Right(Seq("project", "help", "with-defaults"))
    )
  }

  test("build - keep later help and check tokens as project names when they are real ids") {
    val parser = MonorepoCommandParsers.build(Seq("core", "help", "check"))
    val result = ParserImpl.parse(" core help check", parser)

    assertEquals(result, Right(Seq("core", "help", "check")))
  }

  test("build - prefer built-in keywords over colliding project ids") {
    val parser = MonorepoCommandParsers.build(Seq("core", "cross", "with-defaults"))

    assertEquals(ParserImpl.parse(" core cross", parser), Right(Seq("core", "cross")))
    assertEquals(
      ParserImpl.parse(" core with-defaults", parser),
      Right(Seq("core", "with-defaults"))
    )
  }

  test("build - reject trailing tokens after help") {
    fixtureResource.use { fixture =>
      IO {
        val parser = parserFromState(fixture.state)
        val result = ParserImpl.parse(" help extra", parser)

        assert(result.isLeft)
      }
    }
  }

  test("build - reject unknown plain tokens that are not project ids") {
    fixtureResource.use { fixture =>
      IO {
        val parser = parserFromState(fixture.state)
        val result = ParserImpl.parse(" wut", parser)

        assert(result.isLeft)
      }
    }
  }

  test("build - reject unknown hyphenated tokens that are not project ids") {
    fixtureResource.use { fixture =>
      IO {
        val parser = parserFromState(fixture.state)
        val result = ParserImpl.parse(" wit-defaults", parser)

        assert(result.isLeft)
      }
    }
  }

  test("build - reject duplicate project ids instead of silently deduplicating them") {
    val parser = MonorepoCommandParsers.build(Seq("core", "api", "core"))

    assertEquals(ParserImpl.parse(" help", parser), Right(Seq("help")))
    assert(ParserImpl.parse(" core", parser).isLeft)

    MonorepoCommandParsers.validateProjectNames(Seq("core", "api", "core")) match {
      case Left(message) =>
        assert(message.contains("Duplicate configured monorepo project ids"))
        assert(message.contains("core"))
        assert(message.contains("releaseIOMonorepoSelectionProjects"))
      case Right(tokens) =>
        fail(s"Expected duplicate project-name validation to fail but got: $tokens")
    }
  }

  test("build - expose project-name completion from live project ids") {
    fixtureResource.use { fixture =>
      IO {
        val parser      = parserFromState(fixture.state)
        val completions = ParserImpl.completions(parser, " ap", 20).get.map(_.display).toSet

        assert(completions.contains("api"))
      }
    }
  }

  test("build - expose project-name completion after the explicit selector") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-command-parsers-selector-completion") { dir =>
        val coreBase  = new File(dir, "core")
        val crossBase = new File(dir, "cross")
        coreBase.mkdirs()
        crossBase.mkdirs()
        TestSupport.initGitRepo(dir)

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core", "cross")),
          MonorepoSpecSupport.versionedProject("core", coreBase),
          MonorepoSpecSupport.versionedProject("cross", crossBase)
        )
      }
      .use { fixture =>
        IO {
          val parser      = parserFromState(fixture.state)
          val completions =
            ParserImpl.completions(parser, " project cr", 20).get.map(_.display).toSet

          assert(completions.contains("cross"))
        }
      }
  }

  test("buildFromState - allow bare help when project ids cannot be resolved") {
    TestSupport.tempDirResource("monorepo-command-parsers-missing-help").use { dir =>
      IO {
        val state  =
          TestSupport.loadedState(dir, Seq(Project("root", dir)), currentProjectId = Some("root"))
        val parser = MonorepoCommandParsers.buildFromState(state, "releaseIOMonorepo")

        assertEquals(ParserImpl.parse(" help", parser), Right(Seq("help")))
      }
    }
  }

  test("resolveProjectNames - report parser construction failures explicitly") {
    TestSupport.tempDirResource("monorepo-command-parsers-missing-resolve").use { dir =>
      IO {
        val state  =
          TestSupport.loadedState(dir, Seq(Project("root", dir)), currentProjectId = Some("root"))
        val result = MonorepoCommandParsers.resolveProjectNames(state, "releaseIOMonorepo")

        result match {
          case Left(message) =>
            assert(message.contains("Failed to resolve releaseIOMonorepoSelectionProjects"))
          case Right(value)  =>
            fail(s"Expected project-name resolution to fail but got: $value")
        }
      }
    }
  }

  test("buildFromState - reject non-help input when project ids cannot be resolved") {
    TestSupport.tempDirResource("monorepo-command-parsers-missing-check").use { dir =>
      IO {
        val state  =
          TestSupport.loadedState(dir, Seq(Project("root", dir)), currentProjectId = Some("root"))
        val parser = MonorepoCommandParsers.buildFromState(state, "releaseIOMonorepo")
        val result = ParserImpl.parse(" check", parser)

        assert(result.isLeft)
      }
    }
  }

  private def parserFromState(state: sbt.State) =
    MonorepoCommandParsers.buildFromState(state, "releaseIOMonorepo")

  private val fixtureResource: Resource[IO, MonorepoSpecSupport.LoadedFixture] =
    MonorepoSpecSupport.loadedFixtureResource("monorepo-command-parsers") { dir =>
      val coreBase = new File(dir, "core")
      val apiBase  = new File(dir, "api")
      coreBase.mkdirs()
      apiBase.mkdirs()
      TestSupport.initGitRepo(dir)

      Seq(
        MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core", "api")),
        MonorepoSpecSupport.versionedProject("core", coreBase),
        MonorepoSpecSupport.versionedProject("api", apiBase)
      )
    }
}
