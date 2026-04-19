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

      MonorepoPublishSteps.publishArtifacts.execute(ctx, project).map { _ =>
        assert(!new File(fixture.dir, "published.txt").exists())
      }
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
      val seededState = TestSupport.appendSessionSettings(
        fixture.state,
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
