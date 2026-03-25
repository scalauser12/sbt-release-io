package io.release.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.TestSupport
import io.release.steps.VersionSteps
import io.release.steps.VcsSteps
import munit.CatsEffectSuite

import java.io.File

class CorePreflightSpec extends CatsEffectSuite {

  test("renderSummary - include resolved versions, tag status, and workflow flags") {
    val summary = CorePreflight.Summary(
      versionFile = new File("/tmp/version.sbt"),
      currentVersion = "0.1.0-SNAPSHOT",
      releaseVersion = "0.1.0",
      nextVersion = "0.2.0-SNAPSHOT",
      tagName = "v0.1.0",
      tagStatus = "available",
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

  test("check - resolve versions and tag summary without mutating the repository") {
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
        val state       = TestSupport.gitRootState(
          repo,
          Seq(
            io.release.ReleaseIO.releaseIOVersionFile         := versionFile,
            io.release.ReleaseIO.releaseIOReadVersion         := VersionSteps.defaultReadVersion,
            io.release.ReleaseIO.releaseIOVersionFileContents := VersionSteps.defaultWriteVersion(
              useGlobalVersion = true
            ),
            io.release.ReleaseIO.releaseIOUseGlobalVersion    := true,
            io.release.ReleaseIO.releaseIOVersion             := ((_: String) => "0.1.0"),
            io.release.ReleaseIO.releaseIONextVersion         := ((_: String) => "0.2.0-SNAPSHOT"),
            io.release.ReleaseIO.releaseIOTagName             := "v0.1.0",
            io.release.ReleaseIO.releaseIOTagComment          := "Releasing 0.1.0",
            io.release.ReleaseIO.releaseIOVcsSign             := false
          )
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

        for {
          beforeVersion <- IO.blocking(sbt.IO.read(versionFile))
          beforeTags    <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
          summary       <- CorePreflight.check(
                             initialCtx,
                             Seq(VcsSteps.checkCleanWorkingDir),
                             crossBuild = false
                           )
          afterVersion  <- IO.blocking(sbt.IO.read(versionFile))
          afterTags     <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
        } yield {
          assertEquals(summary.currentVersion, "0.1.0-SNAPSHOT")
          assertEquals(summary.releaseVersion, "0.1.0")
          assertEquals(summary.nextVersion, "0.2.0-SNAPSHOT")
          assertEquals(summary.tagName, "v0.1.0")
          assertEquals(summary.tagStatus, "available")
          assertEquals(summary.publishSummary, "step not configured")
          assertEquals(summary.pushSummary, "step not configured")
          assertEquals(summary.stepNames, Seq("check-clean-working-dir"))
          assertEquals(beforeVersion, afterVersion)
          assertEquals(beforeTags.trim, afterTags.trim)
        }
      }
  }
}
