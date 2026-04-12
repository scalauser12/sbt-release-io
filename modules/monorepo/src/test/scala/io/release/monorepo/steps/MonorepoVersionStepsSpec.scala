package io.release.monorepo.internal.steps

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseSharedPlugin.autoImport.*
import io.release.TestAssertions.assertFailure
import io.release.TestAssertions.assertIllegalStateMessage
import io.release.TestSupport
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoReleasePlugin
import io.release.monorepo.MonorepoSpecSupport
import io.release.monorepo.internal.*
import io.release.monorepo.internal.MonorepoComposer
import io.release.monorepo.internal.SelectionMode
import io.release.monorepo.internal.steps.*
import io.release.monorepo.internal.steps.MonorepoVersionStepsSpec.VersionFixture
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.sbt.SbtRuntime
import io.release.version.Version
import munit.CatsEffectSuite

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MonorepoVersionStepsSpec extends CatsEffectSuite {

  test("inquireVersions.validate - fail when the resolved version file does not exist") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-missing-file") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject("core", coreBase)
        )
      }
      .use { fixture =>
        val ctx     = fixture.context(Seq("core"))
        val project = fixture.projectInfo("core")

        assertFailure[IllegalStateException, Unit](
          MonorepoVersionSteps.inquireVersions.validate(ctx, project).void
        ) { err =>
          assert(err.getMessage.contains("Version file not found for core"))
          assert(err.getMessage.contains(project.versionFile.getPath))
        }
      }
  }

  test("inquireVersions.execute - resolve per-project release and next versions") {
    fixtureResource.use { fixture =>
      val ctx     = MonorepoSpecSupport.withPlan(
        fixture.context(Seq("core")),
        MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.ExplicitSelection)
      )
      val project = fixture.projectInfo("core")

      MonorepoVersionSteps.inquireVersions.execute(ctx, project).map { result =>
        val updated = MonorepoSpecSupport.projectNamed(result.projects, "core")

        assertEquals(updated.versions, Some("0.1.0" -> "0.2.0-SNAPSHOT"))
        assertEquals(updated.versionFile, fixture.versionFile)
      }
    }
  }

  test("inquireVersions.execute - fall back to the built-in release version task and warn once") {
    MonorepoVersionStepsSpec
      .fixtureResource(
        "monorepo-version-release-fallback",
        projectSettings = Seq(
          releaseIOVersioningBump        := Version.Bump.Next,
          releaseIOVersioningNextVersion := ((_: String) => "9.9.9-SNAPSHOT")
        )
      )
      .use { fixture =>
        val buffered = MonorepoVersionStepsSpec.bufferedFixture(fixture)
        val ctx      = MonorepoSpecSupport.withPlan(
          buffered.fixture.context(Seq("core")),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.ExplicitSelection)
        )
        val project  = buffered.fixture.projectInfo("core")
        val warning  = MonorepoVersionStepsSpec.missingTaskWarning(
          "core",
          releaseIOVersioningReleaseVersion.key.label,
          s"built-in defaults from ${releaseIOVersioningBump.key.label}"
        )

        for {
          result <- MonorepoVersionSteps.inquireVersions.execute(ctx, project)
          log    <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
        } yield {
          val updated = MonorepoSpecSupport.projectNamed(result.projects, "core")
          assertEquals(updated.versions, Some("0.1.0" -> "9.9.9-SNAPSHOT"))
          assertEquals(TestSupport.warningCount(log, warning), 1)
        }
      }
  }

  test("inquireVersions.execute - fall back to the built-in next version task and warn once") {
    MonorepoVersionStepsSpec
      .fixtureResource(
        "monorepo-version-next-fallback",
        projectSettings = Seq(
          releaseIOVersioningBump           := Version.Bump.Major,
          releaseIOVersioningReleaseVersion := ((version: String) =>
            version.stripSuffix("-SNAPSHOT")
          )
        )
      )
      .use { fixture =>
        val buffered = MonorepoVersionStepsSpec.bufferedFixture(fixture)
        val ctx      = MonorepoSpecSupport.withPlan(
          buffered.fixture.context(Seq("core")),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.ExplicitSelection)
        )
        val project  = buffered.fixture.projectInfo("core")
        val warning  = MonorepoVersionStepsSpec.missingTaskWarning(
          "core",
          releaseIOVersioningNextVersion.key.label,
          s"built-in defaults from ${releaseIOVersioningBump.key.label}"
        )

        for {
          result <- MonorepoVersionSteps.inquireVersions.execute(ctx, project)
          log    <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
        } yield {
          val updated = MonorepoSpecSupport.projectNamed(result.projects, "core")
          assertEquals(updated.versions, Some("0.1.0" -> "1.0.0-SNAPSHOT"))
          assertEquals(TestSupport.warningCount(log, warning), 1)
        }
      }
  }

  test("inquireVersions.execute - fall back to Version.Bump.default and warn once") {
    MonorepoVersionStepsSpec
      .fixtureResource(
        "monorepo-version-bump-fallback",
        projectSettings = Seq(
          releaseIOVersioningReleaseVersion := ((version: String) =>
            version.stripSuffix("-SNAPSHOT")
          )
        )
      )
      .use { fixture =>
        val buffered    = MonorepoVersionStepsSpec.bufferedFixture(fixture)
        val ctx         = MonorepoSpecSupport.withPlan(
          buffered.fixture.context(Seq("core")),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.ExplicitSelection)
        )
        val project     = buffered.fixture.projectInfo("core")
        val nextWarning = MonorepoVersionStepsSpec.missingTaskWarning(
          "core",
          releaseIOVersioningNextVersion.key.label,
          s"built-in defaults from ${releaseIOVersioningBump.key.label}"
        )
        val bumpWarning = MonorepoVersionStepsSpec.missingTaskWarning(
          "core",
          releaseIOVersioningBump.key.label,
          s"${Version.Bump.default}"
        )

        for {
          result <- MonorepoVersionSteps.inquireVersions.execute(ctx, project)
          log    <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
        } yield {
          val updated = MonorepoSpecSupport.projectNamed(result.projects, "core")
          assertEquals(updated.versions, Some("0.1.0" -> "0.1.1-SNAPSHOT"))
          assertEquals(TestSupport.warningCount(log, nextWarning), 1)
          assertEquals(TestSupport.warningCount(log, bumpWarning), 1)
        }
      }
  }

  test("inquireVersions.execute - fail when release version task reports FailureCommand") {
    fixtureResource.use { fixture =>
      val marker       = new File(fixture.loaded.dir, "release-version-task.marker")
      val projectRef   = fixture.loaded.refsById("core")
      val mutatedState = SbtRuntime.appendWithSession(
        fixture.loaded.state,
        Seq(MonorepoStepTestCompat.failureCommandVersionTaskSetting(projectRef, marker))
      )
      val ctx          = MonorepoSpecSupport
        .withPlan(
          fixture.context(Seq("core")),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.ExplicitSelection)
        )
        .withState(mutatedState)
      val project      = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

      assertFailure[IllegalStateException, MonorepoContext](
        MonorepoVersionSteps.inquireVersions.execute(ctx, project)
      ) { err =>
        assert(marker.exists())
        assert(err.getMessage.contains(releaseIOVersioningReleaseVersion.key.label))
        assert(err.getMessage.contains("FailureCommand"))
      }
    }
  }

  test("inquireVersions.execute - fail when next version task reports FailureCommand") {
    fixtureResource.use { fixture =>
      val marker       = new File(fixture.loaded.dir, "next-version-task.marker")
      val projectRef   = fixture.loaded.refsById("core")
      val mutatedState = SbtRuntime.appendWithSession(
        fixture.loaded.state,
        Seq(MonorepoStepTestCompat.failureCommandNextVersionTaskSetting(projectRef, marker))
      )
      val ctx          = MonorepoSpecSupport
        .withPlan(
          fixture.context(Seq("core")),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.ExplicitSelection)
        )
        .withState(mutatedState)
      val project      = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

      assertFailure[IllegalStateException, MonorepoContext](
        MonorepoVersionSteps.inquireVersions.execute(ctx, project)
      ) { err =>
        assert(marker.exists())
        assert(err.getMessage.contains(releaseIOVersioningNextVersion.key.label))
        assert(err.getMessage.contains("FailureCommand"))
      }
    }
  }

  test("inquireVersions.execute - keep state mutations from the next version task") {
    fixtureResource.use { fixture =>
      val projectRef   = fixture.loaded.refsById("core")
      val mutatedState = SbtRuntime.appendWithSession(
        fixture.loaded.state,
        Seq(
          MonorepoStepTestCompat.stateMutationNextVersionTaskSetting(
            projectRef,
            MonorepoVersionStepsSpec.versionTaskStateKey,
            "next-version-task"
          )
        )
      )
      val ctx          = MonorepoSpecSupport
        .withPlan(
          fixture.context(Seq("core")),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.ExplicitSelection)
        )
        .withState(mutatedState)
      val project      = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

      MonorepoVersionSteps.inquireVersions.execute(ctx, project).map { result =>
        assertEquals(
          result.state.get(MonorepoVersionStepsSpec.versionTaskStateKey),
          Some("next-version-task")
        )
        assertEquals(
          MonorepoSpecSupport.projectNamed(result.projects, "core").versions,
          Some("0.1.0" -> "0.2.0-SNAPSHOT")
        )
      }
    }
  }

  test("inquireVersions.execute - fail when stdin closes before the release version prompt") {
    fixtureResource.use { fixture =>
      val buffered = MonorepoVersionStepsSpec.bufferedFixture(fixture)
      val ctx      = MonorepoVersionStepsSpec.promptingContext(buffered.fixture)
      val project  = buffered.fixture.projectInfo("core")

      for {
        _   <- TestSupport.withInput("") {
                 assertIllegalStateMessage(
                   MonorepoVersionSteps.inquireVersions.execute(ctx, project),
                   "Standard input closed while waiting for Release version for core."
                 )
               }
        log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        val warning =
          s"${ReleaseLogPrefixes.Monorepo} Standard input closed while waiting for Release version for core. Aborting."
        assertEquals(TestSupport.warningCount(log, warning), 1)
      }
    }
  }

  test("inquireVersions.execute - fail when stdin closes before the next version prompt") {
    fixtureResource.use { fixture =>
      val buffered = MonorepoVersionStepsSpec.bufferedFixture(fixture)
      val ctx      = MonorepoVersionStepsSpec.promptingContext(buffered.fixture)
      val project  = buffered.fixture.projectInfo("core")

      for {
        _   <- TestSupport.withInput("1.0.0\n") {
                 assertIllegalStateMessage(
                   MonorepoVersionSteps.inquireVersions.execute(ctx, project),
                   "Standard input closed while waiting for Next version for core."
                 )
               }
        log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        val warning =
          s"${ReleaseLogPrefixes.Monorepo} Standard input closed while waiting for Next version for core. Aborting."
        assertEquals(TestSupport.warningCount(log, warning), 1)
      }
    }
  }

  test("setReleaseVersions.execute - write the resolved per-project version file") {
    fixtureResource.use { fixture =>
      val ctx     = fixture.context(
        Seq("core"),
        versionsById = Map("core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"))
      )
      val project = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

      MonorepoVersionSteps.setReleaseVersions.execute(ctx, project) *> IO.blocking {
        val contents = sbt.IO.read(fixture.versionFile)
        assertEquals(contents, """version := "1.0.0"""" + "\n")
      }
    }
  }

  test(
    "setReleaseVersions.execute - fail when a late-bound resolver mutates version files to a shared path"
  ) {
    // Start with distinct per-project version files (the normal setup), then mutate the
    // resolver to a shared file via appendWithSession before executing the write step.
    // This pins down the late-bound scenario: the guard must read the current state at
    // write time, not a cached or pre-mutation snapshot.
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-late-shared") { dir =>
        val coreBase = new File(dir, "core")
        val apiBase  = new File(dir, "api")
        coreBase.mkdirs()
        apiBase.mkdirs()
        sbt.IO.write(new File(coreBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
        sbt.IO.write(new File(apiBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core", "api")),
          MonorepoSpecSupport.versionedProject("core", coreBase),
          MonorepoSpecSupport.versionedProject("api", apiBase)
        )
      }
      .use { fixture =>
        val sharedFile   = new File(fixture.dir, "version.sbt")
        sbt.IO.write(sharedFile, """version := "0.1.0-SNAPSHOT"""" + "\n")
        val mutatedState = SbtRuntime.appendWithSession(
          fixture.state,
          Seq(
            MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
              (_: sbt.ProjectRef, _: sbt.State) =>
                sharedFile
            }
          )
        )
        val ctx          = MonorepoContext(
          state = mutatedState,
          projects = Seq(
            fixture.projectInfo("core", versions = Some("1.0.0" -> "1.1.0-SNAPSHOT"))
          )
        )
        val project      = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

        assertFailure[IllegalStateException, MonorepoContext](
          MonorepoVersionSteps.setReleaseVersions.execute(ctx, project)
        ) { err =>
          assert(
            err.getMessage.contains("Multiple projects resolve to the same version file"),
            s"Expected shared-file error but got: ${err.getMessage}"
          )
          assert(err.getMessage.contains("core"))
          assert(err.getMessage.contains("api"))
        }
      }
  }

  test("setReleaseVersions.execute - validate distinct version files once per write phase") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-write-phase") { dir =>
        val coreBase = new File(dir, "core")
        val apiBase  = new File(dir, "api")
        coreBase.mkdirs()
        apiBase.mkdirs()
        sbt.IO.write(new File(coreBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
        sbt.IO.write(new File(apiBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core", "api")),
          MonorepoSpecSupport.versionedProject("core", coreBase),
          MonorepoSpecSupport.versionedProject("api", apiBase)
        )
      }
      .use { fixture =>
        val resolverCalls = new AtomicInteger(0)
        val countingState = SbtRuntime.appendWithSession(
          fixture.state,
          Seq(
            MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
              (ref: sbt.ProjectRef, _: sbt.State) =>
                resolverCalls.incrementAndGet()
                new File(new File(fixture.dir, ref.project), "version.sbt")
            }
          )
        )
        val ctx           = fixture
          .context(
            Seq("core", "api"),
            versionsById = Map(
              "core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"),
              "api"  -> ("2.0.0" -> "2.1.0-SNAPSHOT")
            )
          )
          .withState(countingState)

        MonorepoComposer.compose(Seq(MonorepoVersionSteps.setReleaseVersions))(ctx).flatMap { _ =>
          IO.blocking {
            assertEquals(resolverCalls.get(), 4)
            assertEquals(
              sbt.IO.read(new File(new File(fixture.dir, "core"), "version.sbt")),
              """version := "1.0.0"""" + "\n"
            )
            assertEquals(
              sbt.IO.read(new File(new File(fixture.dir, "api"), "version.sbt")),
              """version := "2.0.0"""" + "\n"
            )
          }
        }
      }
  }

  test("inquireVersions.execute - keep a release-only override and compute the next version") {
    fixtureResource.use { fixture =>
      val seededCtx = MonorepoSpecSupport
        .withPlan(
          fixture.context(Seq("core")),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.ExplicitSelection)
        )
        .updateProject(fixture.loaded.refsById("core"))(_.copy(versions = Some("1.0.0" -> "")))
      val project   = MonorepoSpecSupport.projectNamed(seededCtx.projects, "core")

      MonorepoVersionSteps.inquireVersions.execute(seededCtx, project).map { result =>
        val updated = MonorepoSpecSupport.projectNamed(result.projects, "core")

        assertEquals(updated.versions, Some("1.0.0" -> "0.2.0-SNAPSHOT"))
        assertEquals(updated.resolvedVersions, Some("1.0.0" -> "0.2.0-SNAPSHOT"))
      }
    }
  }

  test("inquireVersions.execute - compute the release version and keep a next-only override") {
    fixtureResource.use { fixture =>
      val seededCtx = MonorepoSpecSupport
        .withPlan(
          fixture.context(Seq("core")),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.ExplicitSelection)
        )
        .updateProject(fixture.loaded.refsById("core"))(
          _.copy(versions = Some("" -> "1.2.0-SNAPSHOT"))
        )
      val project   = MonorepoSpecSupport.projectNamed(seededCtx.projects, "core")

      MonorepoVersionSteps.inquireVersions.execute(seededCtx, project).map { result =>
        val updated = MonorepoSpecSupport.projectNamed(result.projects, "core")

        assertEquals(updated.versions, Some("0.1.0" -> "1.2.0-SNAPSHOT"))
        assertEquals(updated.resolvedVersions, Some("0.1.0" -> "1.2.0-SNAPSHOT"))
      }
    }
  }

  test("inquireVersions.execute - bypass version tasks when both versions are already resolved") {
    fixtureResource.use { fixture =>
      val releaseMarker = new File(fixture.loaded.dir, "release-version-bypass.marker")
      val nextMarker    = new File(fixture.loaded.dir, "next-version-bypass.marker")
      val projectRef    = fixture.loaded.refsById("core")
      val mutatedState  = SbtRuntime.appendWithSession(
        fixture.loaded.state,
        Seq(
          MonorepoStepTestCompat.failureCommandVersionTaskSetting(projectRef, releaseMarker),
          MonorepoStepTestCompat.failureCommandNextVersionTaskSetting(projectRef, nextMarker)
        )
      )
      val ctx           = MonorepoSpecSupport
        .withPlan(
          fixture.context(
            Seq("core"),
            versionsById = Map("core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"))
          ),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.ExplicitSelection)
        )
        .withState(mutatedState)
      val project       = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

      MonorepoVersionSteps.inquireVersions.execute(ctx, project).map { result =>
        val updated = MonorepoSpecSupport.projectNamed(result.projects, "core")

        assertEquals(updated.versions, Some("1.0.0" -> "1.1.0-SNAPSHOT"))
        assert(
          !releaseMarker.exists(),
          "release version task should not run for resolved overrides"
        )
        assert(!nextMarker.exists(), "next version task should not run for resolved overrides")
      }
    }
  }

  test("setNextVersions.execute - write the next snapshot to the per-project version file") {
    fixtureResource.use { fixture =>
      val ctx     = fixture.context(
        Seq("core"),
        versionsById = Map("core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"))
      )
      val project = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

      MonorepoVersionSteps.setNextVersions.execute(ctx, project) *> IO.blocking {
        val contents = sbt.IO.read(fixture.versionFile)
        assertEquals(contents, """version := "1.1.0-SNAPSHOT"""" + "\n")
      }
    }
  }

  private val fixtureResource: Resource[IO, VersionFixture] =
    MonorepoVersionStepsSpec.fixtureResource(
      "monorepo-version-steps",
      projectSettings = Seq(
        releaseIOVersioningReleaseVersion := ((version: String) =>
          version.stripSuffix("-SNAPSHOT")
        ),
        releaseIOVersioningNextVersion    := ((_: String) => "0.2.0-SNAPSHOT")
      )
    )
}

private object MonorepoVersionStepsSpec {
  private val versionTaskStateKey =
    sbt.AttributeKey[String]("monorepoVersionWorkflowStateMarker")

  final case class BufferedVersionFixture(
      fixture: VersionFixture,
      consoleBuffer: ByteArrayOutputStream
  )

  final case class VersionFixture(
      loaded: MonorepoSpecSupport.LoadedFixture,
      versionFile: File
  ) {
    def context(
        selectedProjectIds: Seq[String],
        versionsById: Map[String, (String, String)] = Map.empty
    ): MonorepoContext =
      loaded.context(selectedProjectIds, versionsById = versionsById)

    def projectInfo(id: String) = loaded.projectInfo(id)
  }

  def bufferedFixture(fixture: VersionFixture): BufferedVersionFixture = {
    val buffered = TestSupport.bufferedState(fixture.loaded.dir)
    val state    = sbt.TestBuildState(
      baseState = buffered.state,
      baseDir = fixture.loaded.dir,
      projects = fixture.loaded.projects,
      currentProjectId = Some("root")
    )
    val refsById =
      SbtRuntime.extracted(state).structure.allProjectRefs.map(ref => ref.project -> ref).toMap

    BufferedVersionFixture(
      fixture = VersionFixture(
        loaded = MonorepoSpecSupport.LoadedFixture(
          dir = fixture.loaded.dir,
          state = state,
          projects = fixture.loaded.projects,
          refsById = refsById
        ),
        versionFile = fixture.versionFile
      ),
      consoleBuffer = buffered.consoleBuffer
    )
  }

  def fixtureResource(
      prefix: String,
      rootSettings: Seq[sbt.Def.Setting[?]] = Nil,
      projectSettings: Seq[sbt.Def.Setting[?]] = Nil
  ): Resource[IO, VersionFixture] =
    MonorepoSpecSupport
      .loadedFixtureResource(prefix) { dir =>
        val coreBase    = new File(dir, "core")
        coreBase.mkdirs()
        val versionFile = new File(coreBase, "version.sbt")
        sbt.IO.write(versionFile, """version := "0.1.0-SNAPSHOT"""" + "\n")

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core"),
            settings = rootSettings
          ),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = projectSettings
          )
        )
      }
      .map { fixture =>
        VersionFixture(
          loaded = fixture,
          versionFile = new File(new File(fixture.dir, "core"), "version.sbt")
        )
      }

  def missingTaskWarning(projectName: String, keyLabel: String, fallback: String): String =
    s"${ReleaseLogPrefixes.Monorepo} $projectName: $keyLabel is undefined; falling back to $fallback"

  def promptingContext(fixture: VersionFixture): MonorepoContext =
    MonorepoSpecSupport.withPlan(
      fixture.loaded.context(Seq("core"), interactive = true),
      MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        flags = MonorepoSpecSupport.defaultFlags.copy(interactive = true)
      )
    )
}
