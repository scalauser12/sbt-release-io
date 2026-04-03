package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.ReleaseTestSupport
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import io.release.steps.ReleaseSteps
import io.release.steps.VcsSteps
import io.release.steps.VersionSteps
import munit.CatsEffectSuite
import sbt.Project

import java.io.File

class CorePreflightSpec extends CatsEffectSuite {

  test("renderSummary - include resolved versions, tag status, and workflow flags") {
    val summary = CorePreflight.Summary(
      versions = CorePreflight.VersionsSummary.Resolved(
        versionFile = new File("/tmp/version.sbt"),
        currentVersion = "0.1.0-SNAPSHOT",
        releaseVersion = "0.1.0",
        nextVersion = "0.2.0-SNAPSHOT"
      ),
      tag = CorePreflight.TagSummary.Resolved(
        tagName = "v0.1.0",
        status = "available"
      ),
      crossBuildEnabled = true,
      publishSummary = "enabled",
      pushSummary = "configured (not executed in check mode)",
      stepNames = Seq("initialize-vcs", "check-clean-working-dir", "tag-release")
    )

    val lines = CorePreflight.renderSummary(summary)

    assert(lines.exists(_.contains("/tmp/version.sbt")))
    assert(lines.exists(_.contains("current version: 0.1.0-SNAPSHOT")))
    assert(lines.exists(_.contains("release version: 0.1.0")))
    assert(lines.exists(_.contains("next version   : 0.2.0-SNAPSHOT")))
    assert(lines.exists(_.contains("tag            : v0.1.0 (available)")))
    assert(lines.exists(_.contains("cross-build    : enabled")))
    assert(lines.exists(_.contains("publish        : enabled")))
    assert(lines.exists(_.contains("push           : configured (not executed in check mode)")))
    assert(
      lines.exists(
        _.contains("steps          : initialize-vcs -> check-clean-working-dir -> tag-release")
      )
    )
  }

  test("helpLines - describe no release side effects and the cross-build caveat") {
    val lines = CorePreflight.helpLines("releaseIO")

    assert(
      lines.exists(
        _.contains(
          "No release side effects: no version-file writes, commits, tags, publish, or push"
        )
      )
    )
    assert(
      lines.exists(
        _.contains(
          "may temporarily switch Scala versions during validation and then restore the entry version"
        )
      )
    )
    assert(lines.exists(_.contains("default-snapshot-dependencies-answer <y|n>")))
    assert(lines.exists(_.contains("default-remote-check-failure-answer <y|n>")))
    assert(lines.exists(_.contains("default-upstream-behind-answer <y|n>")))
    assert(lines.exists(_.contains("default-push-answer <y|n>")))
    assert(lines.exists(_.contains(HelpDocsLinks.CoreReadme)))
  }

  test(
    "check - resolve versions and tag summary when the check process includes the built-in steps"
  ) {
    withInitialContext { case (repo, versionFile, initialCtx) =>
      for {
        beforeVersion <- IO.blocking(sbt.IO.read(versionFile))
        beforeTags    <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
        summary       <- CorePreflight.check(
                           initialCtx,
                           Seq(
                             VcsSteps.checkCleanWorkingDir,
                             VersionSteps.inquireVersions,
                             VcsSteps.tagRelease
                           ),
                           crossBuild = false
                         )
        afterVersion  <- IO.blocking(sbt.IO.read(versionFile))
        afterTags     <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
      } yield {
        assertEquals(
          summary.versions,
          CorePreflight.VersionsSummary.Resolved(
            versionFile = versionFile,
            currentVersion = "0.1.0-SNAPSHOT",
            releaseVersion = "0.1.0",
            nextVersion = "0.2.0-SNAPSHOT"
          )
        )
        assertEquals(
          summary.tag,
          CorePreflight.TagSummary.Resolved("v0.1.0", "available")
        )
        assertEquals(summary.publishSummary, "step not configured")
        assertEquals(summary.pushSummary, "step not configured")
        assertEquals(
          summary.stepNames,
          Seq("check-clean-working-dir", "inquire-versions", "tag-release")
        )
        assertEquals(beforeVersion, afterVersion)
        assertEquals(beforeTags.trim, afterTags.trim)
      }
    }
  }

  test("check - fail validation before version resolution is attempted") {
    withInitialContext { case (repo, _, initialCtx) =>
      val versionResolutionFailure = "version resolution should not run"

      for {
        _            <- IO.blocking(sbt.IO.write(new File(repo, "tracked.txt"), "dirty"))
        mutatedState <- IO.blocking {
                          SbtRuntime.appendWithSession(
                            initialCtx.state,
                            Seq(
                              ReleaseIO.releaseIOVersioningReadVersion := { (_: File) =>
                                IO.raiseError(new IllegalStateException(versionResolutionFailure))
                              }
                            )
                          )
                        }
        dirtyCtx      = initialCtx.withState(mutatedState)
        _            <- assertFailure[IllegalStateException, CorePreflight.Summary](
                          CorePreflight.check(
                            dirtyCtx,
                            Seq(
                              VcsSteps.checkCleanWorkingDir,
                              VersionSteps.inquireVersions
                            ),
                            crossBuild = false
                          )
                        ) { err =>
                          assert(err.getMessage.contains("unstaged modified files"))
                          assert(!err.getMessage.contains(versionResolutionFailure))
                        }
      } yield ()
    }
  }

  test("check - fail fast when validation returns ctx.failWith before version resolution") {
    withInitialContext { case (_, _, initialCtx) =>
      val versionResolutionFailure = "version resolution should not run"
      val failingStep              = ProcessStep
        .single[ReleaseContext]("validation-fail-with")
        .withValidationContext(currentCtx =>
          IO.pure(currentCtx.failWith(new RuntimeException("stop validation")))
        )
        .validateOnly

      for {
        mutatedState <- IO.blocking {
                          SbtRuntime.appendWithSession(
                            initialCtx.state,
                            Seq(
                              ReleaseIO.releaseIOVersioningReadVersion := { (_: File) =>
                                IO.raiseError(new IllegalStateException(versionResolutionFailure))
                              }
                            )
                          )
                        }
        mutatedCtx    = initialCtx.withState(mutatedState)
        _            <- assertFailure[RuntimeException, CorePreflight.Summary](
                          CorePreflight.check(
                            mutatedCtx,
                            Seq(failingStep, VersionSteps.inquireVersions),
                            crossBuild = false
                          )
                        ) { err =>
                          assert(err.getMessage.contains("stop validation"))
                          assert(!err.getMessage.contains(versionResolutionFailure))
                        }
      } yield ()
    }
  }

  test("check - render publish summary from the validated context") {
    withInitialContext { case (_, _, initialCtx) =>
      CorePreflight
        .check(
          initialCtx,
          Seq(
            skipPublishInValidationStep,
            ReleaseSteps.publishArtifacts
          ),
          crossBuild = false
        )
        .map { summary =>
          assertEquals(
            summary.versions,
            CorePreflight.VersionsSummary.NotEvaluated(
              "inquire-versions not in check process"
            )
          )
          assertEquals(
            summary.publishSummary,
            "skipped via releaseIOBehaviorSkipPublish := true"
          )
        }
    }
  }

  test("check - resolve versions and tag summary from the validated state") {
    withInitialContextWithoutExplicitTagName { case (_, versionFile, initialCtx) =>
      CorePreflight
        .check(
          initialCtx,
          Seq(
            overrideVersionTasksInValidationStep(
              releaseVersion = "1.2.3",
              nextVersion = "1.2.4-SNAPSHOT"
            ),
            VersionSteps.inquireVersions,
            VcsSteps.tagRelease
          ),
          crossBuild = false
        )
        .map { summary =>
          assertEquals(
            summary.versions,
            CorePreflight.VersionsSummary.Resolved(
              versionFile = versionFile,
              currentVersion = "0.1.0-SNAPSHOT",
              releaseVersion = "1.2.3",
              nextVersion = "1.2.4-SNAPSHOT"
            )
          )
          assertEquals(
            summary.tag,
            CorePreflight.TagSummary.Resolved("v1.2.3", "available")
          )
        }
    }
  }

  test(
    "check - render versions and tags as not evaluated when the check process omits the built-in steps"
  ) {
    withInitialContext { case (_, _, initialCtx) =>
      CorePreflight
        .check(
          initialCtx,
          Seq(VcsSteps.checkCleanWorkingDir),
          crossBuild = false
        )
        .map { summary =>
          assertEquals(
            summary.versions,
            CorePreflight.VersionsSummary.NotEvaluated(
              "inquire-versions not in check process"
            )
          )
          assertEquals(
            summary.tag,
            CorePreflight.TagSummary.NotEvaluated("tag-release not in check process")
          )
          assertEquals(summary.publishSummary, "step not configured")
          assertEquals(summary.pushSummary, "step not configured")
          assertEquals(summary.stepNames, Seq("check-clean-working-dir"))
        }
    }
  }

  test("check - summarize the compiled hook process and disabled phases") {
    withPluginInitialContext(
      Seq(
        ReleaseIO.releaseIOPolicyEnablePublish := false,
        ReleaseIO.releaseIOPolicyEnablePush    := false,
        ReleaseIO.releaseIOHooksBeforeTag      := Seq(
          ReleaseHookIO.action("before-tag-marker")(_ => IO.unit)
        )
      )
    ) { case (_, versionFile, initialCtx) =>
      val steps = ReleaseHookCompiler.compile(initialCtx.state)

      CorePreflight
        .check(initialCtx, steps, crossBuild = false)
        .map { summary =>
          assertEquals(
            summary.versions,
            CorePreflight.VersionsSummary.Resolved(
              versionFile = versionFile,
              currentVersion = "0.1.0-SNAPSHOT",
              releaseVersion = "0.1.0",
              nextVersion = "0.2.0-SNAPSHOT"
            )
          )
          assertEquals(
            summary.tag,
            CorePreflight.TagSummary.Resolved("v0.1.0", "available")
          )
          assertEquals(summary.publishSummary, "step not configured")
          assertEquals(summary.pushSummary, "step not configured")
          assert(summary.stepNames.contains("before-tag:before-tag-marker"))
          assertEquals(summary.stepNames, steps.map(_.name))
        }
    }
  }

  private def withInitialContext[A](
      f: (File, File, ReleaseContext) => IO[A]
  ): IO[A] =
    ReleaseTestSupport
      .gitRepoWithCommitResource(
        "core-preflight-spec",
        prepareRepo = repo =>
          IO.blocking {
            sbt.IO.write(new File(repo, "tracked.txt"), "initial")
            sbt.IO.write(
              new File(repo, "version.sbt"),
              """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n"
            )
          }
      )
      .use { case (repo, _) =>
        val versionFile = new File(repo, "version.sbt")
        val state       = ReleaseTestSupport.gitRootState(repo, baseVersionSettings(versionFile))
        val initialCtx  = releaseContext(state)

        f(repo, versionFile, initialCtx)
      }

  private def withInitialContextWithoutExplicitTagName[A](
      f: (File, File, ReleaseContext) => IO[A]
  ): IO[A] =
    ReleaseTestSupport
      .gitRepoWithCommitResource(
        "core-preflight-default-tag-spec",
        prepareRepo = repo =>
          IO.blocking {
            sbt.IO.write(new File(repo, "tracked.txt"), "initial")
            sbt.IO.write(
              new File(repo, "version.sbt"),
              """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n"
            )
          }
      )
      .use { case (repo, _) =>
        val versionFile = new File(repo, "version.sbt")
        val state       =
          ReleaseTestSupport.gitRootState(
            repo,
            baseVersionSettings(versionFile, includeExplicitTagName = false)
          )
        val initialCtx  = releaseContext(state)

        f(repo, versionFile, initialCtx)
      }

  private def withPluginInitialContext[A](
      extraSettings: Seq[sbt.Setting[?]]
  )(f: (File, File, ReleaseContext) => IO[A]): IO[A] =
    ReleaseTestSupport
      .gitRepoWithCommitResource(
        "core-preflight-plugin-spec",
        prepareRepo = repo =>
          IO.blocking {
            sbt.IO.write(new File(repo, "tracked.txt"), "initial")
            sbt.IO.write(
              new File(repo, "version.sbt"),
              """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n"
            )
          }
      )
      .use { case (repo, _) =>
        val versionFile = new File(repo, "version.sbt")
        val state       = TestSupport.loadedState(
          repo,
          Seq(
            Project("root", repo).settings(
              (hookSettingsDefaults ++ baseVersionSettings(versionFile) ++ extraSettings)*
            )
          ),
          currentProjectId = Some("root")
        )
        val initialCtx  = ReleaseContext(state = state, interactive = false).withExecutionState(
          CoreExecutionState(
            CoreReleasePlan.build(
              CoreReleasePlan.Inputs(
                useDefaults = false,
                skipTests = false,
                skipPublish = false,
                interactive = false,
                crossBuild = false,
                releaseVersionOverride = None,
                nextVersionOverride = None,
                decisionDefaults = ReleaseDecisionDefaults.empty,
                commandName = "releaseIO"
              )
            )
          )
        )

        f(repo, versionFile, initialCtx)
      }

  private def baseVersionSettings(
      versionFile: File,
      includeExplicitTagName: Boolean = true
  ): Seq[sbt.Setting[?]] =
    Seq(
      io.release.ReleaseIO.releaseIOVersioningFile           := versionFile,
      io.release.ReleaseIO.releaseIOVersioningReadVersion    := VersionSteps.defaultReadVersion,
      io.release.ReleaseIO.releaseIOVersioningFileContents   := VersionSteps.defaultWriteVersion(
        useGlobalVersion = true
      ),
      io.release.ReleaseIO.releaseIOVersioningUseGlobal      := true,
      io.release.ReleaseIO.releaseIOVersioningReleaseVersion := ((_: String) => "0.1.0"),
      io.release.ReleaseIO.releaseIOVersioningNextVersion    := ((_: String) => "0.2.0-SNAPSHOT"),
      io.release.ReleaseIO.releaseIOVcsTagComment            := "Releasing 0.1.0",
      io.release.ReleaseIO.releaseIOVcsSign                  := false,
      io.release.ReleaseIO.releaseIOVcsIgnoreUntrackedFiles  := false
    ) ++
      (if (includeExplicitTagName) Seq(io.release.ReleaseIO.releaseIOVcsTagName := "v0.1.0")
       else
         Seq(
           sbt.ThisBuild / sbt.Keys.version                                     := "0.1.0-SNAPSHOT",
           sbt.Keys.version                                                     := "0.1.0-SNAPSHOT",
           io.release.ReleaseIO.releaseIORuntimeCurrentVersion                  := {
             if (io.release.ReleaseIO.releaseIOVersioningUseGlobal.value)
               (sbt.ThisBuild / sbt.Keys.version).value
             else sbt.Keys.version.value
           },
           io.release.ReleaseIO.releaseIOVcsTagName                             :=
             s"v${io.release.ReleaseIO.releaseIORuntimeCurrentVersion.value}"
         ))

  private def hookSettingsDefaults: Seq[sbt.Setting[?]] =
    Seq(
      ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck := true,
      ReleaseIO.releaseIOPolicyEnableRunClean                  := true,
      ReleaseIO.releaseIOPolicyEnableRunTests                  := true,
      ReleaseIO.releaseIOPolicyEnableTagging                   := true,
      ReleaseIO.releaseIOPolicyEnablePublish                   := true,
      ReleaseIO.releaseIOPolicyEnablePush                      := true,
      ReleaseIO.releaseIOHooksAfterCleanCheck                  := Seq.empty,
      ReleaseIO.releaseIOHooksBeforeVersionResolution          := Seq.empty,
      ReleaseIO.releaseIOHooksAfterVersionResolution           := Seq.empty,
      ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite        := Seq.empty,
      ReleaseIO.releaseIOHooksAfterReleaseVersionWrite         := Seq.empty,
      ReleaseIO.releaseIOHooksBeforeReleaseCommit              := Seq.empty,
      ReleaseIO.releaseIOHooksAfterReleaseCommit               := Seq.empty,
      ReleaseIO.releaseIOHooksBeforeTag                        := Seq.empty,
      ReleaseIO.releaseIOHooksAfterTag                         := Seq.empty,
      ReleaseIO.releaseIOHooksBeforePublish                    := Seq.empty,
      ReleaseIO.releaseIOHooksAfterPublish                     := Seq.empty,
      ReleaseIO.releaseIOHooksBeforeNextVersionWrite           := Seq.empty,
      ReleaseIO.releaseIOHooksAfterNextVersionWrite            := Seq.empty,
      ReleaseIO.releaseIOHooksBeforeNextCommit                 := Seq.empty,
      ReleaseIO.releaseIOHooksAfterNextCommit                  := Seq.empty,
      ReleaseIO.releaseIOHooksBeforePush                       := Seq.empty,
      ReleaseIO.releaseIOHooksAfterPush                        := Seq.empty
    )

  private def releaseContext(state: sbt.State): ReleaseContext =
    ReleaseContext(state = state, interactive = false).withExecutionState(
      CoreExecutionState(
        CoreReleasePlan.build(
          CoreReleasePlan.Inputs(
            useDefaults = false,
            skipTests = false,
            skipPublish = false,
            interactive = false,
            crossBuild = false,
            releaseVersionOverride = None,
            nextVersionOverride = None,
            decisionDefaults = ReleaseDecisionDefaults.empty,
            commandName = "releaseIO"
          )
        )
      )
    )

  private val skipPublishInValidationStep: ProcessStep.Single[ReleaseContext] =
    ProcessStep
      .single[ReleaseContext]("skip-publish-in-validation")
      .withValidationContext(currentCtx => IO.pure(currentCtx.copy(skipPublish = true)))
      .validateOnly

  private def overrideVersionTasksInValidationStep(
      releaseVersion: String,
      nextVersion: String
  ): ProcessStep.Single[ReleaseContext] =
    ProcessStep
      .single[ReleaseContext](s"override-version-tasks-$releaseVersion")
      .withValidationContext { currentCtx =>
        IO.blocking {
          val newState = SbtRuntime.appendWithSession(
            currentCtx.state,
            Seq(
              ReleaseIO.releaseIOVersioningReleaseVersion := ((_: String) => releaseVersion),
              ReleaseIO.releaseIOVersioningNextVersion    := ((_: String) => nextVersion)
            )
          )
          currentCtx.withState(newState)
        }
      }
      .validateOnly
}
