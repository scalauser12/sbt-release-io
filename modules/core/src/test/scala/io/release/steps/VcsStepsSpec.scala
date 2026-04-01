package io.release.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseIO
import io.release.ReleaseIO.releaseIOInternalReleaseHash
import io.release.ReleaseIO.releaseIOInternalReleaseTag
import io.release.ReleaseTestSupport
import io.release.TestAssertions
import io.release.TestSupport
import io.release.internal.CoreExecutionState
import io.release.internal.CoreReleasePlan
import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseDecisionDefaults
import io.release.internal.SbtRuntime
import munit.CatsEffectSuite
import sbt.Keys.packageOptions

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
      VcsSteps.checkCleanWorkingDir.validate(ReleaseContext(state = state))
    }
  }

  test("checkCleanWorkingDir.validate - fail for a dirty tracked file in a loaded repo") {
    ReleaseTestSupport.gitRepoWithLoadedStateResource(fixturePrefix).use { case (repo, state) =>
      IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "modified")) *>
        TestAssertions.assertFailure[IllegalStateException, Unit](
          VcsSteps.checkCleanWorkingDir.validate(ReleaseContext(state = state))
        ) { err =>
          assert(err.getMessage.contains("unstaged modified files"))
          assert(err.getMessage.contains("file.txt"))
        }
    }
  }

  test("pushChanges.validate - pass with a broken tracking remote when upstream is configured") {
    ReleaseTestSupport.brokenRemoteContextResource(fixturePrefix).use { ctx =>
      VcsSteps.pushChanges.validate(ctx)
    }
  }

  test("pushChanges.validate function value - fail when VCS was not initialized") {
    ReleaseTestSupport.gitRepoWithLoadedStateResource(fixturePrefix).use { case (_, state) =>
      val validate = VcsSteps.pushChanges.validate

      TestAssertions.assertFailure[IllegalStateException, Unit](
        validate(ReleaseContext(state = state))
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

  test("tagRelease.execute - abort in non-interactive mode when the tag already exists") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
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
            io.release.ReleaseIO.releaseIOVersionFile         := versionFile,
            io.release.ReleaseIO.releaseIOReadVersion         := VersionSteps.defaultReadVersion,
            io.release.ReleaseIO.releaseIOVersionFileContents := VersionSteps.defaultWriteVersion(
              useGlobalVersion = true
            ),
            io.release.ReleaseIO.releaseIOUseGlobalVersion    := true,
            io.release.ReleaseIO.releaseIOVcsSign             := false,
            io.release.ReleaseIO.releaseIOTagName             := "v1.0.1",
            io.release.ReleaseIO.releaseIOTagComment          := "Releasing 1.0.1"
          )
      )

      for {
        result <- VcsSteps.tagRelease.execute(
                    ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
                  )
        _      <- VcsSteps.checkCleanWorkingDir.validate(result)
        tags   <- IO.blocking(TestSupport.runGit(repo, "tag", "--list", "v1.0.1"))
      } yield {
        assertEquals(tags.trim, "v1.0.1")
        assertEquals(result.vcs.map(_.commandName), Some("git"))
        assert(manifestAttributes(result.state).contains("Vcs-Release-Tag" -> "v1.0.1"))
      }
    }
  }

  test("tagRelease.execute - do not create a tag when releaseIOTagName reports FailureCommand") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val marker = new File(repo, "tag-name-task.marker")
      val state  = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          CoreStepTestCompat.failureCommandTagNameSetting(marker),
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
        )
      )

      for {
        _    <- TestAssertions.assertFailure[IllegalStateException, ReleaseContext](
                  VcsSteps.tagRelease.execute(
                    ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
                  )
                ) { err =>
                  assert(marker.exists())
                  assert(err.getMessage.contains(io.release.ReleaseIO.releaseIOTagName.key.label))
                  assert(err.getMessage.contains("FailureCommand"))
                }
        tags <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
      } yield {
        assertEquals(tags.trim, "")
      }
    }
  }

  test("tagRelease.execute - do not create a tag when releaseIOTagComment reports FailureCommand") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val marker = new File(repo, "tag-comment-task.marker")
      val state  = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign := false,
          io.release.ReleaseIO.releaseIOTagName := "v1.0.0",
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
                  assert(err.getMessage.contains(io.release.ReleaseIO.releaseIOTagComment.key.label))
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
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
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

  test("tagRelease.execute - treat EOF as the default abort answer when the tag already exists") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
        )
      )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        TestSupport.withInput("") {
          TestAssertions.assertIllegalStateMessage(
            VcsSteps.tagRelease.execute(
              ReleaseContext(state = state, vcs = Some(vcs), interactive = true)
            ),
            "Tag [v1.0.0] already exists. Aborting release!"
          )
        }
    }
  }

  test("tagRelease.execute - propagate non-EOF input failures when the tag already exists") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
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
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
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
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
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

  test("preflightTag - trim whitespace around the configured default answer") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
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
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
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

  test("preflightTag - use the configured command name in tag conflict guidance") {
    ReleaseTestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = ReleaseTestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
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

  private def manifestAttributes(state: sbt.State): Set[(String, String)] = {
    val (_, options) = SbtRuntime.extracted(state).runTask(packageOptions, state)

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
    }.toSet
  }

  private def releaseManifestSettings(
      basePackageOptions: Seq[sbt.PackageOption] = Seq.empty
  ): Seq[sbt.Setting[?]] =
    Seq(
      packageOptions               := basePackageOptions,
      releaseIOInternalReleaseHash := None,
      releaseIOInternalReleaseTag  := None,
      packageOptions ++= ReleaseIO.releaseManifestPackageOptions(
        releaseIOInternalReleaseHash.value,
        releaseIOInternalReleaseTag.value
      )
    )
}
