package io.release.monorepo.internal.steps

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseManifestMetadataSupport
import io.release.ReleaseSharedKeys
import io.release.TestAssertions.assertIllegalStateMessage
import io.release.TestSupport
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoReleasePlugin
import io.release.monorepo.MonorepoSpecSupport
import io.release.monorepo.internal.steps.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.PublishValidation
import munit.CatsEffectSuite
import sbt.*
import sbt.Keys.*
import sbt.Resolver

import java.io.ByteArrayOutputStream
import java.io.File

class MonorepoPublishArtifactsSpec extends CatsEffectSuite with MonorepoPublishStepsSpecSupport {

  test("publishArtifacts.validate - fail when checks are enabled and publishTo is empty") {
    singleProjectFixtureResource(
      "monorepo-publish-validate-fail",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { _ =>
      Seq(
        publish / skip := false,
        publishTo      := None
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      assertIllegalStateMessage(
        MonorepoPublishSteps.publishArtifacts.validate(ctx, project),
        PublishValidation.message("core")
      )
    }
  }

  test(
    "publishArtifacts.validate - fail with publishTo error when CLI release-version override " +
      "is present and publish/skip := isSnapshot.value (overlay engages, catches the bypass)"
  ) {
    singleProjectFixtureResource(
      "monorepo-publish-validate-isSnapshot-override",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { _ =>
      Seq(
        version        := "0.1.0-SNAPSHOT",
        // Use the version-dependent skip pattern explicitly (the `isSnapshot`
        // setting isn't always wired by the minimal test loader; expressing the
        // logic directly mirrors what `publish / skip := isSnapshot.value` evaluates
        // to in a real build).
        publish / skip := version.value.endsWith("-SNAPSHOT"),
        // Mirror real "publishTo not configured" with an explicit None so the
        // publishTo task evaluates cleanly to empty (rather than failing to load).
        publishTo      := None
      )
    }.use { fixture =>
      // Mirror the production CLI-override flow: `applyVersionOverrides` populates
      // `project.versions` with the override before main-segment validate runs.
      val ctx     = fixture.context(
        Seq("core"),
        versionsById = Map("core" -> ("1.0.0" -> ""))
      )
      val project = fixture.projectInfo("core")

      assertIllegalStateMessage(
        MonorepoPublishSteps.publishArtifacts.validate(ctx, project),
        PublishValidation.message("core")
      )
    }
  }

  test(
    "publishArtifacts.validate - leave ctx.state unchanged after the local overlay " +
      "(regression: the validate-time overlay must not leak into execute)"
  ) {
    singleProjectFixtureResource(
      "monorepo-publish-validate-no-leak",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        version        := "0.1.0-SNAPSHOT",
        publish / skip := false,
        publishTo      := Some(
          sbt.Resolver.file("local", new File(projectBase.getParentFile, "repo"))
        )
      )
    }.use { fixture =>
      val ctx     = fixture.context(
        Seq("core"),
        versionsById = Map("core" -> ("1.0.0" -> ""))
      )
      val project = fixture.projectInfo("core")
      val ref     = fixture.refsById("core")

      // Before validate: project.ref/version reflects the build setting (snapshot).
      val before = _root_.io.release.runtime.sbt.SbtRuntime.extracted(ctx.state).get(ref / version)
      assertEquals(before, "0.1.0-SNAPSHOT")

      MonorepoPublishSteps.publishArtifacts.validate(ctx, project).map { updated =>
        // The transient overlay was discarded; ctx.state is the original snapshot.
        val after =
          _root_.io.release.runtime.sbt.SbtRuntime.extracted(updated.state).get(ref / version)
        assertEquals(after, "0.1.0-SNAPSHOT")
      }
    }
  }

  test("publishArtifacts.validate - bypass checks when disabled or publish is globally skipped") {
    val checksDisabled = singleProjectFixtureResource(
      "monorepo-publish-validate-disabled",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )
    ) { _ =>
      Seq(
        publish / skip := false,
        publishTo      := None
      )
    }

    val skipPublish = singleProjectFixtureResource(
      "monorepo-publish-validate-skip",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { _ =>
      Seq(
        publish / skip := false,
        publishTo      := None
      )
    }

    Resource.both(checksDisabled, skipPublish).use { case (disabledFixture, skippedFixture) =>
      val disabledCtx     = disabledFixture.context(Seq("core"))
      val disabledProject = disabledFixture.projectInfo("core")
      val skippedCtx      = skippedFixture.context(Seq("core"), skipPublish = true)
      val skippedProject  = skippedFixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.validate(disabledCtx, disabledProject) *>
        MonorepoPublishSteps.publishArtifacts.validate(skippedCtx, skippedProject)
    }
  }

  test("publishArtifacts.execute - skip the publish task when publish / skip is true") {
    singleProjectFixtureResource("monorepo-publish-skip-action") { _ =>
      Seq(
        publish / skip                           := true,
        ReleaseSharedKeys.releaseIOPublishAction := {
          throw new RuntimeException("publish action should not run")
        }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.execute(ctx, project).map { result =>
        assert(!new File(fixture.dir, "published.txt").exists())
        // publishExecutedKeys becomes `Some(...)` so after-publish hooks know
        // the publish step ran, but the per-project key is *not* recorded
        // because the actual publish task was skipped.
        val recorded = result.publishExecutedKeys.getOrElse(Set.empty)
        assertEquals(recorded, Set.empty[String])
      }
    }
  }

  test("publishArtifacts.execute - record the project key when publish actually runs") {
    singleProjectFixtureResource("monorepo-publish-records-outcome") { projectBase =>
      Seq(
        publish / skip                           := false,
        publishTo                                := Some(Resolver.file("local-test", projectBase.getParentFile)),
        ReleaseSharedKeys.releaseIOPublishAction := { /* no-op publish */ }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.execute(ctx, project).map { result =>
        val expectedKey = MonorepoPublishSteps.publishGateKey(result, project)
        assert(
          result.publishExecutedKeys.exists(_.contains(expectedKey)),
          s"Expected publishExecutedKeys to contain $expectedKey, got ${result.publishExecutedKeys}"
        )
      }
    }
  }

  test(
    "publishArtifacts.execute - mark started but not record key when ctx.skipPublish is true"
  ) {
    singleProjectFixtureResource("monorepo-publish-skip-via-context") { _ =>
      Seq(
        publish / skip                           := false,
        ReleaseSharedKeys.releaseIOPublishAction := {
          throw new RuntimeException("publish action should not run")
        }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"), skipPublish = true)
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.execute(ctx, project).map { result =>
        // ctx.skipPublish bypasses publish entirely, so the per-project key is
        // never recorded — but the started marker is set so the after-publish
        // gate distinguishes "publish step ran" from "publish step never ran".
        assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
      }
    }
  }

  test(
    "publishArtifacts: validate freezes skipPublish=true; execute respects the freeze " +
      "even when a hook flipped ctx.skipPublish back to false"
  ) {
    // No publishTo, so if execute were to honor a hook that flipped skipPublish
    // back to false the publish task would run after validation skipped the
    // publishTo check — the freeze is what prevents that bypass.
    singleProjectFixtureResource(
      "monorepo-publish-freeze-skip-true",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { _ =>
      Seq(
        publish / skip                           := false,
        publishTo                                := None,
        ReleaseSharedKeys.releaseIOPublishAction := {
          throw new RuntimeException("publish action should not run")
        }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"), skipPublish = true)
      val project = fixture.projectInfo("core")

      for {
        validated  <- MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
        _           = assertEquals(validated.publishSkipFrozen, Some(true))
        hookFlipped = validated.copy(skipPublish = false)
        result     <- MonorepoPublishSteps.publishArtifacts.execute(hookFlipped, project)
        _           = assert(!result.failed)
        _           = assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
      } yield ()
    }
  }

  test(
    "publishArtifacts: validate freezes skipPublish=false; execute still skips when a hook " +
      "flips ctx.skipPublish to true (preserves the documented hook pattern)"
  ) {
    singleProjectFixtureResource(
      "monorepo-publish-freeze-flip-true",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        publish / skip                           := false,
        publishTo                                := Some(
          Resolver.file("local", new File(projectBase.getParentFile, "repo"))
        ),
        ReleaseSharedKeys.releaseIOPublishAction := {
          throw new RuntimeException("publish action should not run")
        }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      for {
        validated  <- MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
        _           = assertEquals(validated.publishSkipFrozen, Some(false))
        hookFlipped = validated.copy(skipPublish = true)
        result     <- MonorepoPublishSteps.publishArtifacts.execute(hookFlipped, project)
        _           = assert(!result.failed)
        _           = assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
      } yield ()
    }
  }

  test("publishArtifacts.execute - run the configured publish task when publish is enabled") {
    singleProjectFixtureResource("monorepo-publish-run-action") { projectBase =>
      val marker         = new File(projectBase.getParentFile, "published.txt")
      val fallbackMarker = new File(projectBase.getParentFile, "publish-fallback.txt")

      Seq(
        publish / skip                           := false,
        publishTo                                := Some(Resolver.file("local-test", projectBase.getParentFile)),
        publish                                  := {
          sbt.IO.write(fallbackMarker, "fallback")
        },
        ReleaseSharedKeys.releaseIOPublishAction := {
          sbt.IO.write(marker, "published")
        }
      )
    }.use { fixture =>
      val buffered = MonorepoPublishArtifactsSpec.bufferedFixture(fixture)
      val ctx      = buffered.fixture.context(Seq("core"))
      val project  = buffered.fixture.projectInfo("core")
      val warning  = MonorepoPublishArtifactsSpec.publishFallbackWarning("core")

      for {
        _   <- MonorepoPublishSteps.publishArtifacts.execute(ctx, project)
        log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        assert(new File(fixture.dir, "published.txt").exists())
        assert(!new File(fixture.dir, "publish-fallback.txt").exists())
        assertEquals(TestSupport.warningCount(log, warning), 0)
      }
    }
  }

  test(
    "publishArtifacts.execute - fall back to publish and warn when releaseIOPublishAction is undefined"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-fallback-action") { dir =>
        val projectBase = new File(dir, "core")
        projectBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport
            .versionedProject(
              "core",
              projectBase,
              settings = Seq(
                publish / skip := false,
                publishTo      := Some(Resolver.file("local-test", projectBase.getParentFile)),
                publish        := {
                  Def
                    .task(())
                    .updateState { (state: State, _: Unit) =>
                      state.put(MonorepoPublishArtifactsSpec.executionStateKey, "publish")
                    }
                    .value
                }
              )
            )
            .enablePlugins(sbt.plugins.JvmPlugin)
        )
      }
      .use { fixture =>
        val buffered = MonorepoPublishArtifactsSpec.bufferedFixture(fixture)
        val ctx      = buffered.fixture.context(Seq("core"))
        val project  = buffered.fixture.projectInfo("core")
        val warning  = MonorepoPublishArtifactsSpec.publishFallbackWarning("core")

        for {
          result <- MonorepoPublishSteps.publishArtifacts.execute(ctx, project)
          log    <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
        } yield {
          assertEquals(
            result.state.get(MonorepoPublishArtifactsSpec.executionStateKey),
            Some("publish")
          )
          assertEquals(TestSupport.warningCount(log, warning), 1)
        }
      }
  }

  test(
    "publishArtifacts.execute - honor ThisBuild releaseIOPublishAction without fallback warning"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-thisbuild-action") { dir =>
        val projectBase     = new File(dir, "core")
        projectBase.mkdirs()
        val rootSettings    = Seq(
          ThisBuild / ReleaseSharedKeys.releaseIOPublishAction := {
            Def
              .task(())
              .updateState { (state: State, _: Unit) =>
                state.put(MonorepoPublishArtifactsSpec.executionStateKey, "thisbuild")
              }
              .value
          }
        )
        val projectSettings = Seq(
          publish / skip := false,
          publishTo      := Some(Resolver.file("local-test", projectBase.getParentFile)),
          publish        := {
            Def
              .task(())
              .updateState { (state: State, _: Unit) =>
                state.put(MonorepoPublishArtifactsSpec.executionStateKey, "fallback")
              }
              .value
          }
        )

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core"),
            settings = rootSettings
          ),
          MonorepoSpecSupport
            .versionedProject(
              "core",
              projectBase,
              settings = projectSettings
            )
            .enablePlugins(sbt.plugins.JvmPlugin)
        )
      }
      .use { fixture =>
        val buffered = MonorepoPublishArtifactsSpec.bufferedFixture(fixture)
        val ctx      = buffered.fixture.context(Seq("core"))
        val project  = buffered.fixture.projectInfo("core")
        val warning  = MonorepoPublishArtifactsSpec.publishFallbackWarning("core")

        for {
          result <- MonorepoPublishSteps.publishArtifacts.execute(ctx, project)
          log    <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
        } yield {
          assertEquals(
            result.state.get(MonorepoPublishArtifactsSpec.executionStateKey),
            Some("thisbuild")
          )
          assertEquals(TestSupport.warningCount(log, warning), 0)
        }
      }
  }

  test("publishArtifacts.execute - run publish with the release version and release metadata") {
    singleProjectFixtureResource("monorepo-publish-release-metadata") { projectBase =>
      val marker = new File(projectBase.getParentFile, "publish-metadata.txt")

      Seq(
        publish / skip                                              := false,
        publishTo                                                   := Some(Resolver.file("local-test", projectBase.getParentFile)),
        version                                                     := "0.1.0-SNAPSHOT",
        packageOptions                                              := Seq.empty,
        ReleaseManifestMetadataSupport.releaseIOInternalReleaseHash := None,
        ReleaseManifestMetadataSupport.releaseIOInternalReleaseTag  := None,
        packageOptions ++= ReleaseManifestMetadataSupport
          .releaseManifestPackageOptions(
            ReleaseManifestMetadataSupport.releaseIOInternalReleaseHash.value,
            ReleaseManifestMetadataSupport.releaseIOInternalReleaseTag.value
          ),
        ReleaseSharedKeys.releaseIOPublishAction                    := {
          def manifestEntries(options: Seq[PackageOption]): Map[String, String] =
            options.flatMap {
              case product: Product if product.productPrefix == "ManifestAttributes" =>
                product.productElement(0) match {
                  case entries: Seq[?] @unchecked =>
                    entries.collect { case (name, value: String) =>
                      name.toString -> value
                    }
                  case _                          => Seq.empty
                }
              case _                                                                 => Seq.empty
            }.toMap

          val entries = manifestEntries(packageOptions.value)
          sbt.IO.write(
            marker,
            Seq(
              s"version=${version.value}",
              s"hash=${entries.getOrElse("Vcs-Release-Hash", "")}",
              s"tag=${entries.getOrElse("Vcs-Release-Tag", "")}"
            ).mkString("", "\n", "\n")
          )
        }
      )
    }.use { fixture =>
      val coreRef     = fixture.refsById("core")
      // Mirror what the real release pipeline installs by the time
      // publishArtifacts.execute runs: per-project release version (from
      // set-release-version), release hash (from commit-release-versions),
      // and release tag (from tag-releases-per-project) — all installed via
      // appendSessionSettings so they live in session.rawAppend.
      val seededState = TestSupport.appendSessionSettings(
        fixture.state,
        Seq(coreRef / version := "1.0.0") ++
          ReleaseManifestMetadataSupport
            .releaseManifestHashSettings(Seq(coreRef), "abc123") ++
          ReleaseManifestMetadataSupport
            .releaseManifestTagSettings(coreRef, "core/v1.0.0")
      )
      val project     = fixture.projectInfo(
        "core",
        versions = Some("1.0.0" -> "1.1.0-SNAPSHOT"),
        tagName = Some("core/v1.0.0")
      )
      val ctx         = MonorepoContext(state = seededState, projects = Seq(project))

      MonorepoPublishSteps.publishArtifacts.execute(ctx, project).flatMap { _ =>
        IO.blocking {
          val lines = sbt.IO.readLines(new File(fixture.dir, "publish-metadata.txt"))
          assertEquals(lines, List("version=1.0.0", "hash=abc123", "tag=core/v1.0.0"))
        }
      }
    }
  }

  test("publishArtifacts.validate - pass when publishTo is set and publish not skipped") {
    singleProjectFixtureResource(
      "monorepo-publish-validate-pass",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        publish / skip := false,
        publishTo      := Some(Resolver.file("local-test", projectBase.getParentFile))
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
    }
  }
}

private object MonorepoPublishArtifactsSpec {
  val executionStateKey: AttributeKey[String] =
    AttributeKey[String]("monorepoPublishArtifactsSpecExecution")

  final case class BufferedFixture(
      fixture: MonorepoSpecSupport.LoadedFixture,
      consoleBuffer: ByteArrayOutputStream
  )

  def bufferedFixture(fixture: MonorepoSpecSupport.LoadedFixture): BufferedFixture = {
    val buffered = TestSupport.bufferedState(fixture.dir)
    val state    = sbt.TestBuildState(
      baseState = buffered.state,
      baseDir = fixture.dir,
      projects = fixture.projects,
      currentProjectId = Some("root")
    )
    val refsById =
      SbtRuntime.extracted(state).structure.allProjectRefs.map(ref => ref.project -> ref).toMap

    BufferedFixture(
      fixture = MonorepoSpecSupport.LoadedFixture(
        dir = fixture.dir,
        state = state,
        projects = fixture.projects,
        refsById = refsById
      ),
      consoleBuffer = buffered.consoleBuffer
    )
  }

  def publishFallbackWarning(projectName: String): String =
    s"${ReleaseLogPrefixes.Monorepo} $projectName: " +
      s"${ReleaseSharedKeys.releaseIOPublishAction.key.label} is undefined; " +
      s"falling back to ${publish.key.label}"
}
