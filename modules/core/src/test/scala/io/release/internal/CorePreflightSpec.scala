package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.TestSupport
import io.release.steps.VersionSteps
import io.release.steps.VcsSteps
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
        ReleaseIO.releaseIOEnablePublish  := false,
        ReleaseIO.releaseIOEnablePush     := false,
        ReleaseIO.releaseIOBeforeTagHooks := Seq(
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
    TestSupport
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
        val state       = TestSupport.gitRootState(repo, baseVersionSettings(versionFile))
        val initialCtx  = releaseContext(state)

        f(repo, versionFile, initialCtx)
      }

  private def withPluginInitialContext[A](
      extraSettings: Seq[sbt.Setting[?]]
  )(f: (File, File, ReleaseContext) => IO[A]): IO[A] =
    TestSupport
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
                tagDefault = None,
                commandName = "releaseIO"
              )
            )
          )
        )

        f(repo, versionFile, initialCtx)
      }

  private def baseVersionSettings(versionFile: File): Seq[sbt.Setting[?]] =
    Seq(
      io.release.ReleaseIO.releaseIOVersionFile          := versionFile,
      io.release.ReleaseIO.releaseIOReadVersion          := VersionSteps.defaultReadVersion,
      io.release.ReleaseIO.releaseIOVersionFileContents  := VersionSteps.defaultWriteVersion(
        useGlobalVersion = true
      ),
      io.release.ReleaseIO.releaseIOUseGlobalVersion     := true,
      io.release.ReleaseIO.releaseIOVersion              := ((_: String) => "0.1.0"),
      io.release.ReleaseIO.releaseIONextVersion          := ((_: String) => "0.2.0-SNAPSHOT"),
      io.release.ReleaseIO.releaseIOTagName              := "v0.1.0",
      io.release.ReleaseIO.releaseIOTagComment           := "Releasing 0.1.0",
      io.release.ReleaseIO.releaseIOVcsSign              := false,
      io.release.ReleaseIO.releaseIOIgnoreUntrackedFiles := false
    )

  private def hookSettingsDefaults: Seq[sbt.Setting[?]] =
    Seq(
      ReleaseIO.releaseIOEnableSnapshotDependenciesCheck := true,
      ReleaseIO.releaseIOEnableRunClean                  := true,
      ReleaseIO.releaseIOEnableRunTests                  := true,
      ReleaseIO.releaseIOEnableTagging                   := true,
      ReleaseIO.releaseIOEnablePublish                   := true,
      ReleaseIO.releaseIOEnablePush                      := true,
      ReleaseIO.releaseIOAfterCleanCheckHooks            := Seq.empty,
      ReleaseIO.releaseIOBeforeVersionResolutionHooks    := Seq.empty,
      ReleaseIO.releaseIOAfterVersionResolutionHooks     := Seq.empty,
      ReleaseIO.releaseIOBeforeReleaseVersionWriteHooks  := Seq.empty,
      ReleaseIO.releaseIOAfterReleaseVersionWriteHooks   := Seq.empty,
      ReleaseIO.releaseIOBeforeReleaseCommitHooks        := Seq.empty,
      ReleaseIO.releaseIOAfterReleaseCommitHooks         := Seq.empty,
      ReleaseIO.releaseIOBeforeTagHooks                  := Seq.empty,
      ReleaseIO.releaseIOAfterTagHooks                   := Seq.empty,
      ReleaseIO.releaseIOBeforePublishHooks              := Seq.empty,
      ReleaseIO.releaseIOAfterPublishHooks               := Seq.empty,
      ReleaseIO.releaseIOBeforeNextVersionWriteHooks     := Seq.empty,
      ReleaseIO.releaseIOAfterNextVersionWriteHooks      := Seq.empty,
      ReleaseIO.releaseIOBeforeNextCommitHooks           := Seq.empty,
      ReleaseIO.releaseIOAfterNextCommitHooks            := Seq.empty,
      ReleaseIO.releaseIOBeforePushHooks                 := Seq.empty,
      ReleaseIO.releaseIOAfterPushHooks                  := Seq.empty
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
            tagDefault = None,
            commandName = "releaseIO"
          )
        )
      )
    )
}
