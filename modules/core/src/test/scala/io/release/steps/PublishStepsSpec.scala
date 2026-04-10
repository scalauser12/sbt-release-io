package io.release.core.internal.steps

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseContext
import io.release.ReleaseIOCompat
import io.release.ReleasePluginIO
import io.release.ReleaseTestSupport
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.sbt.SbtCompat
import munit.CatsEffectSuite
import sbt.*
import sbt.Keys.*

import java.io.File

class PublishStepsSpec extends CatsEffectSuite {
  private val fixturePrefix                = "publish-steps-spec"
  private val snapshotDependenciesKeyLabel =
    ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies.key.label

  // ── publishArtifacts.execute ────────────────────────────────────────

  test(
    "publishArtifacts.execute - fail with an attributed cause when the publish task " +
      "reports FailureCommand"
  ) {
    loadedContextResource(s"$fixturePrefix-publish") { dir =>
      val marker = new File(dir, "publish-ran.txt")
      marker -> Seq(CoreStepTestCompat.failureCommandPublishTaskSetting(marker))
    }.use { case (ctx, marker) =>
      PublishSteps.publishArtifacts.execute(ctx).map { result =>
        assert(result.failed)
        assert(marker.exists())
        assertEquals(result.state.remainingCommands, Nil)
        assert(
          result.failureCause.exists(
            _.getMessage.contains(
              "publish-artifacts: sbt task " +
                s"'${ReleasePluginIO.autoImport.releaseIOPublishAction.key.label}'"
            )
          )
        )
      }
    }
  }

  test("publishArtifacts.execute - skip when skipPublish is true") {
    ReleaseTestSupport.dummyContextResource(s"$fixturePrefix-skip-pub").use { ctx =>
      val skipped = ctx.copy(skipPublish = true)
      PublishSteps.publishArtifacts.execute(skipped).map { result =>
        assert(!result.failed)
      }
    }
  }

  // ── publishArtifacts.validate ───────────────────────────────────────

  test("checkSnapshotDependencies.validate - fail on snapshot dependencies") {
    loadedContextResource(s"$fixturePrefix-snapshots") { _ =>
      () -> Seq(
        ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies := Seq(
          "org.example" % "dep" % "1.0.0-SNAPSHOT"
        )
      )
    }.use { case (ctx, _) =>
      assertFailure[IllegalStateException, Unit](
        PublishSteps.checkSnapshotDependencies.validate(ctx).void
      ) { err =>
        assert(err.getMessage.contains("Snapshot dependencies found"))
        assert(err.getMessage.contains("org.example:dep:1.0.0-SNAPSHOT"))
      }
    }
  }

  test("checkSnapshotDependencies.validate function value - fail on snapshot dependencies") {
    loadedContextResource(s"$fixturePrefix-snapshots-field") { _ =>
      () -> Seq(
        ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies := Seq(
          "org.example" % "dep" % "1.0.0-SNAPSHOT"
        )
      )
    }.use { case (ctx, _) =>
      val validate = PublishSteps.checkSnapshotDependencies.validate

      assertFailure[IllegalStateException, Unit](validate(ctx).void) { err =>
        assert(err.getMessage.contains("Snapshot dependencies found"))
        assert(err.getMessage.contains("org.example:dep:1.0.0-SNAPSHOT"))
      }
    }
  }

  test("checkSnapshotDependencies.validate - fail when diagnostics task reports FailureCommand") {
    loadedContextResource(s"$fixturePrefix-snapshots-failure-command") { dir =>
      val marker = new File(dir, "snapshot-deps-ran.txt")
      marker -> Seq(CoreStepTestCompat.failureCommandSnapshotDependenciesTaskSetting(marker))
    }.use { case (ctx, marker) =>
      assertFailure[IllegalStateException, Unit](
        PublishSteps.checkSnapshotDependencies.validate(ctx).void
      ) { err =>
        assert(marker.exists())
        assert(err.getMessage.contains("FailureCommand"))
        assert(err.getMessage.contains(snapshotDependenciesKeyLabel))
      }
    }
  }

  test("checkSnapshotDependencies.validate - list duplicate aggregated snapshot coordinates once") {
    val dep = "org.example" % "dep" % "1.0.0-SNAPSHOT"

    multiProjectContextResource(
      s"$fixturePrefix-snapshots-distinct",
      rootSettings = Seq(
        ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies := Seq(dep)
      ),
      childSettings = Seq(
        ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies := Seq(dep)
      )
    ).use { case (ctx, _) =>
      assertFailure[IllegalStateException, Unit](
        PublishSteps.checkSnapshotDependencies.validate(ctx).void
      ) { err =>
        assert(err.getMessage.contains("Snapshot dependencies found"))
        assertEquals(
          err.getMessage.linesIterator.count(_.contains("org.example:dep:1.0.0-SNAPSHOT")),
          1
        )
      }
    }
  }

  test("publishArtifacts.validate - short-circuit when publishArtifactsChecks is false") {
    loadedContextResource(s"$fixturePrefix-val-off") { _ =>
      () -> Seq(ReleasePluginIO.autoImport.releaseIOPublishChecks := false)
    }.use { case (ctx, _) =>
      PublishSteps.publishArtifacts.validate(ctx).void
    }
  }

  test("publishArtifacts.validate - pass when publish/skip is true") {
    loadedContextResource(s"$fixturePrefix-val-skip") { _ =>
      () -> Seq(
        ReleasePluginIO.autoImport.releaseIOPublishChecks := true,
        publish / skip                                    := true
      )
    }.use { case (ctx, _) =>
      PublishSteps.publishArtifacts.validate(ctx).void
    }
  }

  test("publishArtifacts.validate - fail when publishTo is missing and checks enabled") {
    loadedContextResource(s"$fixturePrefix-val-missing") { _ =>
      () -> Seq(ReleasePluginIO.autoImport.releaseIOPublishChecks := true)
    }.use { case (ctx, _) =>
      assertFailure[IllegalStateException, Unit](
        PublishSteps.publishArtifacts.validate(ctx).void
      )(err => assert(err.getMessage.contains("publishTo not configured")))
    }
  }

  test(
    "publishArtifacts.validate - treat publishTo eval error as missing (fail validation)"
  ) {
    bufferedLoadedContextResource(s"$fixturePrefix-val-throw-pt") { _ =>
      () -> Seq(
        ReleasePluginIO.autoImport.releaseIOPublishChecks := true,
        CoreStepTestCompat.throwingPublishToSetting
      )
    }.use { case (ctx, _, consoleBuffer) =>
      val warningPrefix =
        s"${ReleaseLogPrefixes.Core} Failed to evaluate publishTo for root: "

      for {
        _   <- assertFailure[IllegalStateException, Unit](
                 PublishSteps.publishArtifacts.validate(ctx).void
               )(err => assert(err.getMessage.contains("publishTo not configured")))
        log <- IO(consoleBuffer.toString("UTF-8"))
      } yield {
        assertEquals(TestSupport.warningCount(log, warningPrefix), 1)
      }
    }
  }

  test(
    "publishArtifacts.validate - treat publish/skip eval error as not skipped " +
      "(pass when publishTo is set)"
  ) {
    bufferedLoadedContextResource(s"$fixturePrefix-val-throw-ps") { dir =>
      () -> Seq(
        ReleasePluginIO.autoImport.releaseIOPublishChecks := true,
        CoreStepTestCompat.throwingPublishSkipSetting,
        publishTo                                         := Some(Resolver.file("local", new File(dir, "repo")))
      )
    }.use { case (ctx, _, consoleBuffer) =>
      val warningPrefix =
        s"${ReleaseLogPrefixes.Core} Failed to evaluate publish / skip for root: "

      for {
        _   <- PublishSteps.publishArtifacts.validate(ctx).void
        log <- IO(consoleBuffer.toString("UTF-8"))
      } yield {
        assertEquals(TestSupport.warningCount(log, warningPrefix), 1)
      }
    }
  }

  test(
    "publishArtifacts.validate - pass when child has no publishTo but " +
      "publish task aggregate is false"
  ) {
    multiProjectContextResource(
      s"$fixturePrefix-val-agg-false",
      rootSettings = Seq(
        ReleasePluginIO.autoImport.releaseIOPublishChecks                  := true,
        ReleasePluginIO.autoImport.releaseIOPublishAction / Keys.aggregate := false,
        publishTo                                                          := Some(
          Resolver.file("local", new File("target/repo"))
        )
      ),
      childSettings = Seq.empty // no publishTo on child
    ).use { case (ctx, _) =>
      PublishSteps.publishArtifacts.validate(ctx).void
    }
  }

  test(
    "publishArtifacts.validate - fail when child has no publishTo and " +
      "publish task aggregate is true"
  ) {
    multiProjectContextResource(
      s"$fixturePrefix-val-agg-true",
      rootSettings = Seq(
        ReleasePluginIO.autoImport.releaseIOPublishChecks                  := true,
        ReleasePluginIO.autoImport.releaseIOPublishAction / Keys.aggregate := true,
        publishTo                                                          := Some(
          Resolver.file("local", new File("target/repo"))
        )
      ),
      childSettings = Seq.empty // no publishTo on child
    ).use { case (ctx, _) =>
      assertFailure[IllegalStateException, Unit](
        PublishSteps.publishArtifacts.validate(ctx).void
      )(err => assert(err.getMessage.contains("publishTo not configured")))
    }
  }

  // ── runTests.execute ────────────────────────────────────────────────

  test(
    "runTests.execute - fail with an attributed cause when the test task " +
      "reports FailureCommand"
  ) {
    loadedContextResource(s"$fixturePrefix-tests") { dir =>
      val marker = new File(dir, "test-ran.txt")
      marker -> Seq(CoreStepTestCompat.failureCommandTestTaskSetting(marker))
    }.use { case (ctx, marker) =>
      PublishSteps.runTests.execute(ctx).map { result =>
        assert(result.failed)
        assert(marker.exists())
        assertEquals(result.state.remainingCommands, Nil)
        assert(
          result.failureCause.exists(
            _.getMessage.contains(
              s"run-tests: sbt task 'Test / ${ReleaseIOCompat.testKey.key.label}'"
            )
          )
        )
      }
    }
  }

  test("runTests.execute - skip when skipTests is true") {
    ReleaseTestSupport.dummyContextResource(s"$fixturePrefix-skip-test").use { ctx =>
      val skipped = ctx.copy(skipTests = true)
      PublishSteps.runTests.execute(skipped).map { result =>
        assert(!result.failed)
      }
    }
  }

  // ── runClean.execute ────────────────────────────────────────────────

  test("runClean.execute - succeed when clean reports no failure") {
    loadedContextResource(s"$fixturePrefix-clean-ok") { _ =>
      () -> Seq.empty[Setting[?]]
    }.use { case (ctx, _) =>
      PublishSteps.runClean.execute(ctx).map { result =>
        assert(!result.failed)
      }
    }
  }

  // ── failOnSbtTaskFailure ────────────────────────────────────────────

  test("failOnSbtTaskFailure - strip FailureCommand and retain the clean failure cause") {
    ReleaseTestSupport.dummyContextResource(s"$fixturePrefix-clean").use { ctx =>
      IO {
        val message  =
          "run-clean: clean action reported failure via FailureCommand"
        val newState =
          ctx.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil)
        val result   = PublishSteps.failOnSbtTaskFailure(ctx, newState, message)

        assert(result.failed)
        assertEquals(result.state.remainingCommands, Nil)
        assertEquals(result.failureCause.map(_.getMessage), Some(message))
      }
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private def multiProjectContextResource(
      prefix: String,
      rootSettings: Seq[Setting[?]],
      childSettings: Seq[Setting[?]]
  ): Resource[IO, (ReleaseContext, Unit)] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val childBase = new File(dir, "child")
        childBase.mkdirs()
        val state     = TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir)
              .aggregate(LocalProject("child"))
              .settings(rootSettings*),
            Project("child", childBase)
              .settings(childSettings*)
          ),
          currentProjectId = Some("root")
        )
        (ReleaseContext(state = state), ())
      }
    }

  private def loadedContextResource[A](
      prefix: String
  )(prepare: File => (A, Seq[Setting[?]])): Resource[IO, (ReleaseContext, A)] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val (value, settings) = prepare(dir)
        val state             = TestSupport.loadedState(
          dir,
          Seq(Project("root", dir).settings(settings*)),
          currentProjectId = Some("root")
        )
        (ReleaseContext(state = state), value)
      }
    }

  private def bufferedLoadedContextResource[A](
      prefix: String
  )(
      prepare: File => (A, Seq[Setting[?]])
  ): Resource[IO, (ReleaseContext, A, java.io.ByteArrayOutputStream)] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val (value, settings) = prepare(dir)
        val buffered          = TestSupport.bufferedState(dir)
        val state             = sbt.TestBuildState(
          baseState = buffered.state,
          baseDir = dir,
          projects = Seq(Project("root", dir).settings(settings*)),
          currentProjectId = Some("root")
        )
        (ReleaseContext(state = state), value, buffered.consoleBuffer)
      }
    }
}
