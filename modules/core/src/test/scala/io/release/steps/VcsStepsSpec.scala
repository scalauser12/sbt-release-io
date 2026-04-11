package io.release.core.internal.steps

import _root_.io.release.ReleaseManifestMetadataSupport.releaseIOInternalReleaseHash
import _root_.io.release.ReleaseManifestMetadataSupport.releaseIOInternalReleaseTag
import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleasePluginIO
import io.release.ReleaseTestSupport
import io.release.TestAssertions
import io.release.TestSupport
import io.release.core.internal.CoreExecutionState
import io.release.core.internal.CoreReleasePlan
import io.release.runtime.ExecutionFlags
import io.release.runtime.ReleaseDecisionDefaults
import io.release.runtime.ReleaseLogPrefixes
import io.release.vcs.TagConflictResolver
import munit.CatsEffectSuite
import sbt.Keys.packageOptions
import sbt.Project

import java.io.File
import java.io.IOException
import java.io.InputStream

class VcsStepsSpec extends CatsEffectSuite {
  private val fixturePrefix = "vcs-steps-spec"

  test("initializeVcs - detect Git from the loaded project base") {
    ReleaseTestSupport.gitRepoWithLoadedStateResource(fixturePrefix).use { case (_, state) =>
      VcsSteps.initializeVcs.execute(ReleaseContext(state = state)).map { result =>
        assertEquals(result.vcs.map(_.commandName), Some("git"))
      }
    }
  }

  test("checkCleanWorkingDir.validate - succeed for a clean loaded repo") {
    ReleaseTestSupport.gitRepoWithLoadedStateResource(fixturePrefix).use { case (_, state) =>
      VcsSteps.checkCleanWorkingDir.validate(ReleaseContext(state = state)).void
    }
  }

  test("checkCleanWorkingDir.validate - fail for a dirty tracked file in a loaded repo") {
    ReleaseTestSupport.gitRepoWithLoadedStateResource(fixturePrefix).use { case (repo, state) =>
      IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "modified")) *>
        TestAssertions.assertFailure[IllegalStateException, Unit](
          VcsSteps.checkCleanWorkingDir.validate(ReleaseContext(state = state)).void
        ) { err =>
          assert(err.getMessage.contains("unstaged modified files"))
          assert(err.getMessage.contains("file.txt"))
        }
    }
  }

  test("pushChanges.validate - pass with a broken tracking remote when upstream is configured") {
    ReleaseTestSupport.brokenRemoteContextResource(fixturePrefix).use { ctx =>
      VcsSteps.pushChanges.validate(ctx).void
    }
  }

  test("pushChanges.validate function value - fail when VCS was not initialized") {
    ReleaseTestSupport.gitRepoWithLoadedStateResource(fixturePrefix).use { case (_, state) =>
      val validate = VcsSteps.pushChanges.validate

      TestAssertions.assertFailure[IllegalStateException, Unit](
        validate(ReleaseContext(state = state)).void
      ) { err =>
        assertEquals(
          err.getMessage,
          "VCS not initialized. Ensure initializeVcs runs before this step."
        )
      }
    }
  }

  test("pushChanges.execute - fail during remote preflight in non-interactive mode") {
    ReleaseTestSupport.brokenRemoteContextResource(fixturePrefix).use { ctx =>
      TestAssertions.assertFailure[IllegalStateException, ReleaseContext](
        VcsSteps.pushChanges.execute(ctx)
      )(err => assert(err.getMessage.contains("Aborting the release due to remote check failure.")))
    }
  }

  test("pushChanges.execute - push the tracked branch and follow tags to the remote") {
    TestSupport.gitRepoWithBareRemoteResource(fixturePrefix).use { case (repo, remoteRepo) =>
      for {
        vcs        <- ReleaseTestSupport.detectVcs(repo)
        _          <- IO.blocking {
                        sbt.IO.write(new File(repo, "file.txt"), "updated")
                        TestSupport.commitAll(repo, "Second commit")
                      }
        _          <- vcs.tag("v1.0.1", "Release 1.0.1", sign = false)
        result     <- VcsSteps.pushChanges.execute(
                        ReleaseContext(
                          state = ReleaseTestSupport.gitRootState(repo),
                          vcs = Some(vcs),
                          interactive = false
                        ).withExecutionState(
                          CoreExecutionState(
                            CoreReleasePlan(
                              flags = ExecutionFlags(
                                useDefaults = true,
                                skipTests = false,
                                skipPublish = false,
                                interactive = false,
                                crossBuild = false
                              ),
                              releaseVersionOverride = None,
                              nextVersionOverride = None,
                              decisionDefaults = ReleaseDecisionDefaults.empty
                            )
                          )
                        )
                      )
        localHead  <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
        remoteHead <-
          IO.blocking(
            TestSupport.runGit(remoteRepo, "rev-parse", "--verify", "refs/heads/main").trim
          )
        remoteTag  <- IO.blocking(TestSupport.runGit(remoteRepo, "tag", "--list", "v1.0.1").trim)
      } yield {
        assert(!result.failed)
        assertEquals(remoteHead, localHead)
        assertEquals(remoteTag, "v1.0.1")
      }
    }
  }

  test("tagRelease.execute - abort in non-interactive mode when the tag already exists") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        TestAssertions.assertIllegalStateMessage(
          VcsSteps.tagRelease
            .execute(ReleaseContext(state = state, vcs = Some(vcs), interactive = false)),
          "Tag [v1.0.0] already exists. Aborting release in non-interactive mode."
        )
    }
  }

  test("tagRelease.execute - create the tag and keep the resulting context usable") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val versionFile = new File(repo, "version.sbt")
      val state       = ReleaseTestSupport.gitRootState(
        repo,
        releaseManifestSettings() ++
          Seq(
            io.release.ReleasePluginIO.autoImport.releaseIOVersioningFile         := versionFile,
            io.release.ReleasePluginIO.autoImport.releaseIOVersioningReadVersion  := VersionSteps.defaultReadVersion,
            io.release.ReleasePluginIO.autoImport.releaseIOVersioningFileContents := VersionSteps
              .defaultWriteVersion(
                useGlobalVersion = true
              ),
            io.release.ReleasePluginIO.autoImport.releaseIOVersioningUseGlobal    := true,
            io.release.ReleasePluginIO.autoImport.releaseIOVcsSign                := false,
            io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName             := "v1.0.1",
            io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment          := "Releasing 1.0.1"
          )
      )

      for {
        result <- VcsSteps.tagRelease.execute(
                    ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
                  )
        _      <- VcsSteps.checkCleanWorkingDir.validate(result).void
        tags   <- IO.blocking(TestSupport.runGit(repo, "tag", "--list", "v1.0.1"))
      } yield {
        assertEquals(tags.trim, "v1.0.1")
        assertEquals(result.vcs.map(_.commandName), Some("git"))
        assert(
          TestSupport.manifestAttributes(result.state).contains("Vcs-Release-Tag" -> "v1.0.1")
        )
      }
    }
  }

  test("tagRelease.execute - do not create a tag when releaseIOVcsTagName reports FailureCommand") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val marker = new File(repo, "tag-name-task.marker")
      val state  = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          CoreStepTestCompat.failureCommandTagNameSetting(marker),
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )

      for {
        _    <- TestAssertions.assertFailure[IllegalStateException, ReleaseContext](
                  VcsSteps.tagRelease.execute(
                    ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
                  )
                ) { err =>
                  assert(marker.exists())
                  assert(
                    err.getMessage
                      .contains(io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName.key.label)
                  )
                  assert(err.getMessage.contains("FailureCommand"))
                }
        tags <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
      } yield {
        assertEquals(tags.trim, "")
      }
    }
  }

  test(
    "tagRelease.execute - do not create a tag when releaseIOVcsTagComment reports FailureCommand"
  ) {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val marker = new File(repo, "tag-comment-task.marker")
      val state  = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign    := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName := "v1.0.0",
          CoreStepTestCompat.failureCommandTagCommentSetting(marker)
        )
      )

      for {
        _    <- TestAssertions.assertFailure[IllegalStateException, ReleaseContext](
                  VcsSteps.tagRelease.execute(
                    ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
                  )
                ) { err =>
                  assert(marker.exists())
                  assert(
                    err.getMessage
                      .contains(io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment.key.label)
                  )
                  assert(err.getMessage.contains("FailureCommand"))
                }
        tags <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
      } yield {
        assertEquals(tags.trim, "")
      }
    }
  }

  test("tagRelease.execute - trim whitespace around interactive keep input") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        TestSupport
          .withInput(" k \n") {
            VcsSteps.tagRelease.execute(
              ReleaseContext(state = state, vcs = Some(vcs), interactive = true)
            )
          }
          .flatMap { result =>
            IO.blocking(TestSupport.runGit(repo, "tag", "--list")).map { tags =>
              assertEquals(tags.trim, "v1.0.0")
              assertEquals(result.vcs.map(_.commandName), Some("git"))
            }
          }
    }
  }

  test("tagRelease.execute - keep a tag on HEAD when the persisted release hash is stale") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val baseState = ReleaseTestSupport.gitRootState(
        repo,
        releaseManifestSettings() ++ Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )

      for {
        releaseCommitHash <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
        _                 <- IO.blocking {
                               sbt.IO.write(new File(repo, "file.txt"), "updated-after-hook")
                               TestSupport.commitAll(repo, "Post-commit hook commit")
                             }
        headRev           <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
        _                 <- IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0"))
        staleState         = TestSupport.appendSessionSettings(
                               baseState,
                               Seq(releaseIOInternalReleaseHash := Some(releaseCommitHash))
                             )
        result            <- TestSupport.withInput("k\n") {
                               VcsSteps.tagRelease.execute(
                                 ReleaseContext(
                                   state = staleState,
                                   vcs = Some(vcs),
                                   interactive = true
                                 )
                               )
                             }
        tagRev            <- IO.blocking(
                               TestSupport.runGit(repo, "rev-list", "-n", "1", "v1.0.0").trim
                             )
      } yield {
        assertEquals(tagRev, headRev)
        assertEquals(result.vcs.map(_.commandName), Some("git"))
      }
    }
  }

  test("tagRelease.execute - reject keep when the existing tag points at another commit") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )

      for {
        _              <- IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0"))
        originalTagRev <- IO.blocking(
                            TestSupport.runGit(repo, "rev-list", "-n", "1", "v1.0.0").trim
                          )
        _              <- IO.blocking {
                            sbt.IO.write(new File(repo, "file.txt"), "updated")
                            TestSupport.commitAll(repo, "Second commit")
                          }
        headRev        <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
        _              <- TestAssertions.assertFailure[IllegalStateException, ReleaseContext](
                            TestSupport.withInput("k\n") {
                              VcsSteps.tagRelease.execute(
                                ReleaseContext(
                                  state = state,
                                  vcs = Some(vcs),
                                  interactive = true
                                )
                              )
                            }
                          ) { err =>
                            assert(err.getMessage.contains(s"Tag [v1.0.0] already exists"))
                            assert(err.getMessage.contains(originalTagRev))
                            assert(err.getMessage.contains(headRev))
                            assert(err.getMessage.contains("Overwrite it or provide a new tag."))
                          }
        tagRev         <- IO.blocking(
                            TestSupport.runGit(repo, "rev-list", "-n", "1", "v1.0.0").trim
                          )
      } yield {
        assertEquals(tagRev, originalTagRev)
        assertNotEquals(tagRev, headRev)
      }
    }
  }

  test("tagRelease.execute - treat EOF as the default abort answer when the tag already exists") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val buffered = bufferedGitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )

      for {
        _   <- IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0"))
        _   <- TestSupport.withInput("") {
                 TestAssertions.assertIllegalStateMessage(
                   VcsSteps.tagRelease.execute(
                     ReleaseContext(state = buffered.state, vcs = Some(vcs), interactive = true)
                   ),
                   "Tag [v1.0.0] already exists. Aborting release!"
                 )
               }
        log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        val warning =
          s"${ReleaseLogPrefixes.Core} Standard input closed before tag conflict resolution. Aborting."
        assertEquals(TestSupport.warningCount(log, warning), 1)
      }
    }
  }

  test("tagRelease.execute - propagate non-EOF input failures when the tag already exists") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )

      val brokenInput = new InputStream {
        override def read(): Int =
          throw new IOException("broken stdin")
      }

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        TestSupport.withSystemInput(brokenInput) {
          TestAssertions.assertFailure[IOException, ReleaseContext](
            VcsSteps.tagRelease.execute(
              ReleaseContext(state = state, vcs = Some(vcs), interactive = true)
            )
          ) { err =>
            assertEquals(err.getMessage, "broken stdin")
          }
        }
    }
  }

  test("preflightTag - fail deterministically in non-interactive mode when the tag exists") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        TestAssertions.assertFailure[IllegalStateException, VcsSteps.PreflightTagOutcome](
          VcsSteps.preflightTag(
            ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
              .withVersions("1.0.0", "1.1.0-SNAPSHOT")
          )
        ) { err =>
          assert(err.getMessage.contains("Current settings would abort in non-interactive mode"))
          assert(err.getMessage.contains("releaseIO help"))
        }
    }
  }

  test("preflightTag - report keep behavior when the configured default answer is keep") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )
      val ctx   = ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
        .withVersions("1.0.0", "1.1.0-SNAPSHOT")
        .withExecutionState(
          CoreExecutionState(
            CoreReleasePlan(
              flags = ExecutionFlags(
                useDefaults = false,
                skipTests = false,
                skipPublish = false,
                interactive = false,
                crossBuild = false
              ),
              releaseVersionOverride = None,
              nextVersionOverride = None,
              decisionDefaults = ReleaseDecisionDefaults.empty.copy(tagExistsAnswer = Some("k"))
            )
          )
        )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        VcsSteps.preflightTag(ctx).map { outcome =>
          assertEquals(outcome.tagName, "v1.0.0")
          assertEquals(outcome.status, "exists; release will keep the existing tag")
        }
    }
  }

  test("preflightTag - keep a tag on HEAD when the persisted release hash is stale") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val baseState = ReleaseTestSupport.gitRootState(
        repo,
        releaseManifestSettings() ++ Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )

      for {
        releaseCommitHash <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
        _                 <- IO.blocking {
                               sbt.IO.write(new File(repo, "file.txt"), "updated-after-hook")
                               TestSupport.commitAll(repo, "Post-commit hook commit")
                             }
        _                 <- IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0"))
        staleState         = TestSupport.appendSessionSettings(
                               baseState,
                               Seq(releaseIOInternalReleaseHash := Some(releaseCommitHash))
                             )
        ctx                = ReleaseContext(state = staleState, vcs = Some(vcs), interactive = false)
                               .withVersions("1.0.0", "1.1.0-SNAPSHOT")
                               .withExecutionState(
                                 CoreExecutionState(
                                   CoreReleasePlan(
                                     flags = ExecutionFlags(
                                       useDefaults = false,
                                       skipTests = false,
                                       skipPublish = false,
                                       interactive = false,
                                       crossBuild = false
                                     ),
                                     releaseVersionOverride = None,
                                     nextVersionOverride = None,
                                     decisionDefaults =
                                       ReleaseDecisionDefaults.empty.copy(tagExistsAnswer = Some("k"))
                                   )
                                 )
                               )
        outcome           <- VcsSteps.preflightTag(ctx)
      } yield {
        assertEquals(outcome.tagName, "v1.0.0")
        assertEquals(outcome.status, "exists; release will keep the existing tag")
      }
    }
  }

  test("preflightTag - reject the configured keep answer when the existing tag points elsewhere") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )
      val ctx   = ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
        .withVersions("1.0.0", "1.1.0-SNAPSHOT")
        .withExecutionState(
          CoreExecutionState(
            CoreReleasePlan(
              flags = ExecutionFlags(
                useDefaults = false,
                skipTests = false,
                skipPublish = false,
                interactive = false,
                crossBuild = false
              ),
              releaseVersionOverride = None,
              nextVersionOverride = None,
              decisionDefaults = ReleaseDecisionDefaults.empty.copy(tagExistsAnswer = Some("k"))
            )
          )
        )

      for {
        _              <- IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0"))
        originalTagRev <- IO.blocking(
                            TestSupport.runGit(repo, "rev-list", "-n", "1", "v1.0.0").trim
                          )
        _              <- IO.blocking {
                            sbt.IO.write(new File(repo, "file.txt"), "updated")
                            TestSupport.commitAll(repo, "Second commit")
                          }
        headRev        <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
        _              <-
          TestAssertions.assertFailure[IllegalStateException, VcsSteps.PreflightTagOutcome](
            VcsSteps.preflightTag(ctx)
          ) { err =>
            assert(err.getMessage.contains(s"Tag [v1.0.0] already exists"))
            assert(err.getMessage.contains(originalTagRev))
            assert(err.getMessage.contains(headRev))
            assert(err.getMessage.contains("releaseIO help"))
          }
      } yield ()
    }
  }

  test(
    "preflightTag - reject the configured keep answer when release will create a new commit"
  ) {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )
      val ctx   = ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
        .withVersions("1.0.0", "1.1.0-SNAPSHOT")
        .withExecutionState(
          CoreExecutionState(
            CoreReleasePlan(
              flags = ExecutionFlags(
                useDefaults = false,
                skipTests = false,
                skipPublish = false,
                interactive = false,
                crossBuild = false
              ),
              releaseVersionOverride = None,
              nextVersionOverride = None,
              decisionDefaults = ReleaseDecisionDefaults.empty.copy(tagExistsAnswer = Some("k"))
            )
          )
        )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        TestAssertions.assertFailure[IllegalStateException, VcsSteps.PreflightTagOutcome](
          VcsSteps.preflightTag(
            ctx,
            _ => IO.pure(TagConflictResolver.PreflightCommitTarget.FutureReleaseCommit)
          )
        ) { err =>
          assert(
            err.getMessage.contains(
              "This release will create a new commit before tagging, so keeping the existing tag is not valid."
            )
          )
          assert(err.getMessage.contains("releaseIO help"))
        }
    }
  }

  test("preflightTag - trim whitespace around the configured default answer") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )
      val ctx   = ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
        .withVersions("1.0.0", "1.1.0-SNAPSHOT")
        .withExecutionState(
          CoreExecutionState(
            CoreReleasePlan(
              flags = ExecutionFlags(
                useDefaults = false,
                skipTests = false,
                skipPublish = false,
                interactive = false,
                crossBuild = false
              ),
              releaseVersionOverride = None,
              nextVersionOverride = None,
              decisionDefaults = ReleaseDecisionDefaults.empty.copy(tagExistsAnswer = Some(" k "))
            )
          )
        )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        VcsSteps.preflightTag(ctx).map { outcome =>
          assertEquals(outcome.tagName, "v1.0.0")
          assertEquals(outcome.status, "exists; release will keep the existing tag")
        }
    }
  }

  test("preflightTag - report interactive prompt behavior when the tag exists") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        VcsSteps
          .preflightTag(
            ReleaseContext(state = state, vcs = Some(vcs), interactive = true)
              .withVersions("1.0.0", "1.1.0-SNAPSHOT")
          )
          .map { outcome =>
            assertEquals(outcome.tagName, "v1.0.0")
            assertEquals(
              outcome.status,
              "exists; interactive release will prompt for overwrite, keep, abort, or a new tag"
            )
          }
    }
  }

  test("preflightTag - omit keep from the interactive status for a future release commit") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        VcsSteps
          .preflightTag(
            ReleaseContext(state = state, vcs = Some(vcs), interactive = true)
              .withVersions("1.0.0", "1.1.0-SNAPSHOT"),
            _ => IO.pure(TagConflictResolver.PreflightCommitTarget.FutureReleaseCommit)
          )
          .map { outcome =>
            assertEquals(outcome.tagName, "v1.0.0")
            assertEquals(
              outcome.status,
              "exists; release will create a new commit before tagging, so interactive release will prompt for overwrite, abort, or a new tag"
            )
          }
    }
  }

  test("preflightTag - ignore the persisted release hash for a future release commit") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val baseState = ReleaseTestSupport.gitRootState(
        repo,
        releaseManifestSettings() ++ Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )

      for {
        _       <- IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0"))
        headRev <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
        state    = TestSupport.appendSessionSettings(
                     baseState,
                     Seq(releaseIOInternalReleaseHash := Some(headRev))
                   )
        ctx      = ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
                     .withExecutionState(
                       CoreExecutionState(
                         CoreReleasePlan(
                           flags = ExecutionFlags(
                             useDefaults = false,
                             skipTests = false,
                             skipPublish = false,
                             interactive = false,
                             crossBuild = false
                           ),
                           releaseVersionOverride = None,
                           nextVersionOverride = None,
                           decisionDefaults =
                             ReleaseDecisionDefaults.empty.copy(tagExistsAnswer = Some("k"))
                         )
                       )
                     )
        _       <- TestAssertions.assertFailure[IllegalStateException, VcsSteps.PreflightTagOutcome](
                     VcsSteps.preflightTag(
                       ctx,
                       _ => IO.pure(TagConflictResolver.PreflightCommitTarget.FutureReleaseCommit)
                     )
                   ) { err =>
                     assert(
                       err.getMessage.contains(
                         "This release will create a new commit before tagging, so keeping the existing tag is not valid."
                       )
                     )
                     assert(err.getMessage.contains("releaseIO help"))
                   }
      } yield ()
    }
  }

  test("preflightTag - use the configured command name in tag conflict guidance") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleasePluginIO.autoImport.releaseIOVcsSign       := false,
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagName    := "v1.0.0",
          io.release.ReleasePluginIO.autoImport.releaseIOVcsTagComment := "Releasing 1.0.0"
        )
      )
      val ctx   = ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
        .withVersions("1.0.0", "1.1.0-SNAPSHOT")
        .withExecutionState(
          CoreExecutionState(
            CoreReleasePlan(
              flags = ExecutionFlags(
                useDefaults = false,
                skipTests = false,
                skipPublish = false,
                interactive = false,
                crossBuild = false
              ),
              releaseVersionOverride = None,
              nextVersionOverride = None,
              decisionDefaults = ReleaseDecisionDefaults.empty,
              commandName = "releaseCustom"
            )
          )
        )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        TestAssertions.assertFailure[IllegalStateException, VcsSteps.PreflightTagOutcome](
          VcsSteps.preflightTag(ctx)
        ) { err =>
          assert(err.getMessage.contains("releaseCustom help"))
          assert(!err.getMessage.contains("releaseIO help"))
        }
    }
  }

  private def releaseManifestSettings(
      basePackageOptions: Seq[sbt.PackageOption] = Seq.empty
  ): Seq[sbt.Setting[?]] =
    Seq(
      packageOptions               := basePackageOptions,
      releaseIOInternalReleaseHash := None,
      releaseIOInternalReleaseTag  := None,
      packageOptions ++= _root_.io.release.ReleaseManifestMetadataSupport
        .releaseManifestPackageOptions(
          releaseIOInternalReleaseHash.value,
          releaseIOInternalReleaseTag.value
        )
    )

  private def bufferedGitRootState(
      repo: File,
      rootSettings: Seq[sbt.Setting[?]]
  ): TestSupport.BufferedState = {
    val buffered = TestSupport.bufferedState(repo)
    val state    = sbt.TestBuildState(
      baseState = buffered.state,
      baseDir = repo,
      projects = Seq(
        Project("root", repo).settings(
          (Seq(
            ReleasePluginIO.autoImport.releaseIOVcsIgnoreUntrackedFiles := false
          ) ++ rootSettings)*
        )
      ),
      currentProjectId = Some("root")
    )

    buffered.copy(state = state)
  }

}
