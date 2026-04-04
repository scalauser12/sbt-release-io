package io.release.steps

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Ref
import io.release.ReleaseContext
import io.release.ReleaseIO
import io.release.ReleaseIO.*
import io.release.ReleaseTestSupport
import io.release.TestAssertions
import io.release.TestSupport
import io.release.internal.CoreExecutionState
import io.release.internal.CoreReleasePlan
import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseDecisionDefaults
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.Keys.packageOptions
import sbt.Project

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class VersionStepsSpec extends CatsEffectSuite {
  private val fixturePrefix = "version-steps-spec"

  private val startupFlags = ExecutionFlags(
    useDefaults = false,
    skipTests = false,
    skipPublish = false,
    interactive = false,
    crossBuild = false
  )

  test("resolveVersionPlan - use live version settings even when a startup plan is attached") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { baseCtx =>
      val dir          = baseCtx.state.configuration.baseDirectory
      val resolvedFile = new File(dir, "resolved-version.sbt")
      val ctx          = withStartupPlan(baseCtx, "1.2.3", "1.2.4-SNAPSHOT")

      val result = VersionSteps.resolveVersionPlan(
        ctx,
        _ =>
          VersionSteps.ResolvedSettings(
            versionFile = resolvedFile,
            readVersion = _ => IO.pure("1.2.3-SNAPSHOT"),
            versionFileContents = (_, version) => IO.pure(s"resolved=$version"),
            useGlobalVersion = true
          )
      )

      for {
        readContents <- result.readVersion(resolvedFile)
        fileContents <- result.versionFileContents(resolvedFile, "1.2.3")
      } yield {
        assertEquals(result.versionFile, resolvedFile)
        assertEquals(result.releaseVersionOverride, Some("1.2.3"))
        assertEquals(result.nextVersionOverride, Some("1.2.4-SNAPSHOT"))
        assert(result.useGlobalVersion)
        assertEquals(readContents, "1.2.3-SNAPSHOT")
        assertEquals(fileContents, "resolved=1.2.3")
      }
    }
  }

  test("resolveVersionPlan - leave overrides empty when no execution state is attached") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val dir      = ctx.state.configuration.baseDirectory
      val liveFile = new File(dir, "live-version.sbt")

      val result = VersionSteps.resolveVersionPlan(
        ctx,
        _ =>
          VersionSteps.ResolvedSettings(
            versionFile = liveFile,
            readVersion = _ => IO.pure("0.9.0-SNAPSHOT"),
            versionFileContents = (_, version) => IO.pure(s"live=$version"),
            useGlobalVersion = false
          )
      )

      for {
        readContents <- result.readVersion(liveFile)
        fileContents <- result.versionFileContents(liveFile, "1.0.0")
      } yield {
        assertEquals(result.versionFile, liveFile)
        assertEquals(result.releaseVersionOverride, None)
        assertEquals(result.nextVersionOverride, None)
        assert(!result.useGlobalVersion)
        assertEquals(readContents, "0.9.0-SNAPSHOT")
        assertEquals(fileContents, "live=1.0.0")
      }
    }
  }

  test(
    "resolveVersionPlan - delegate live resolution to CoreVersionResolver and read overrides"
  ) {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { baseCtx =>
      val dir          = baseCtx.state.configuration.baseDirectory
      val fallbackFile = new File(dir, "fallback-version.sbt")
      val resolverRuns = new AtomicInteger(0)
      val ctx          = withStartupPlan(baseCtx, "2.0.0", "2.0.1-SNAPSHOT")

      val result = VersionSteps.resolveVersionPlan(
        ctx,
        _ => {
          resolverRuns.incrementAndGet()
          VersionSteps.ResolvedSettings(
            versionFile = fallbackFile,
            readVersion = _ => IO.pure("1.9.9-SNAPSHOT"),
            versionFileContents = (_, version) => IO.pure(s"fallback=$version"),
            useGlobalVersion = false
          )
        }
      )

      for {
        readContents <- result.readVersion(fallbackFile)
        fileContents <- result.versionFileContents(fallbackFile, "2.0.0")
      } yield {
        assertEquals(resolverRuns.get(), 1)
        assertEquals(result.versionFile, fallbackFile)
        assertEquals(result.releaseVersionOverride, Some("2.0.0"))
        assertEquals(result.nextVersionOverride, Some("2.0.1-SNAPSHOT"))
        assert(!result.useGlobalVersion)
        assertEquals(readContents, "1.9.9-SNAPSHOT")
        assertEquals(fileContents, "fallback=2.0.0")
      }
    }
  }

  private val defaultReadVersionCases = Seq(
    (
      "defaultReadVersion - parse a standard version line",
      """ThisBuild / version := "1.2.3-SNAPSHOT"""",
      "1.2.3-SNAPSHOT"
    ),
    (
      "defaultReadVersion - skip single-line // comments",
      """// version := "9.9.9"
        |version := "0.1.0"
        |""".stripMargin,
      "0.1.0"
    ),
    (
      "defaultReadVersion - skip versions inside multiline block comments",
      """/*
        |ThisBuild / version := "9.9.9"
        |*/
        |ThisBuild / version := "0.1.0-SNAPSHOT"
        |""".stripMargin,
      "0.1.0-SNAPSHOT"
    ),
    (
      "defaultReadVersion - skip single-line block comments",
      """/* version := "9.9.9" */
        |version := "0.1.0"
        |""".stripMargin,
      "0.1.0"
    ),
    (
      "defaultReadVersion - skip block comments with *-prefixed lines",
      """/*
        | * version := "9.9.9"
        | */
        |version := "0.1.0"
        |""".stripMargin,
      "0.1.0"
    )
  )

  defaultReadVersionCases.foreach { case (name, contents, expected) =>
    test(name) {
      TestSupport
        .tempDirResource(fixturePrefix)
        .use(dir => assertReadVersion(dir, contents, expected))
    }
  }

  test("defaultReadVersion - raise IllegalStateException when no version can be parsed") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      writeVersionFile(
        dir,
        """// version := "9.9.9"
          |/*
          |ThisBuild / version := "0.1.0"
          |*/
          |lazy val root = project
          |""".stripMargin
      ).flatMap { file =>
        TestAssertions.assertFailure[IllegalStateException, String](
          VersionSteps.defaultReadVersion(file)
        ) { err =>
          assert(err.getMessage.contains("Could not parse version"))
          assert(err.getMessage.contains(file.getName))
        }
      }
    }
  }

  test("resolveVersions - compute defaults without prompting when prompts are disabled") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      writeVersionFile(dir, """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n").flatMap {
        versionFile =>
          val state = TestSupport.loadedState(
            dir,
            Seq(
              Project("root", dir).settings(
                releaseIOVersioningFile           := versionFile,
                releaseIOVersioningReadVersion    := VersionSteps.defaultReadVersion,
                releaseIOVersioningFileContents   := VersionSteps
                  .defaultWriteVersion(useGlobalVersion = true),
                releaseIOVersioningUseGlobal      := true,
                releaseIOVersioningReleaseVersion := (_.stripSuffix("-SNAPSHOT")),
                releaseIOVersioningNextVersion    := (_ => "0.2.0-SNAPSHOT")
              )
            )
          )
          val ctx   = promptingContext(state)

          VersionSteps.resolveVersions(ctx, allowPrompts = false).map { case (_, resolved) =>
            assertEquals(resolved.versionFile.getName, "version.sbt")
            assertEquals(resolved.currentVersion, "0.1.0-SNAPSHOT")
            assertEquals(resolved.releaseVersion, "0.1.0")
            assertEquals(resolved.nextVersion, "0.2.0-SNAPSHOT")
          }
      }
    }
  }

  test("resolveVersions - fail when stdin closes before the release version prompt is answered") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      writeVersionFile(dir, """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n").flatMap {
        versionFile =>
          val buffered = bufferedLoadedState(
            dir,
            Seq(
              releaseIOVersioningFile           := versionFile,
              releaseIOVersioningReadVersion    := VersionSteps.defaultReadVersion,
              releaseIOVersioningFileContents   := VersionSteps.defaultWriteVersion(
                useGlobalVersion = true
              ),
              releaseIOVersioningUseGlobal      := true,
              releaseIOVersioningReleaseVersion := (_.stripSuffix("-SNAPSHOT")),
              releaseIOVersioningNextVersion    := (_ => "0.2.0-SNAPSHOT")
            )
          )

          for {
            _   <- TestSupport.withInput("") {
                     TestAssertions.assertIllegalStateMessage(
                       VersionSteps
                         .resolveVersions(promptingContext(buffered.state), allowPrompts = true),
                       "Standard input closed while waiting for Release version."
                     )
                   }
            log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
          } yield {
            val warning =
              s"${ReleaseLogPrefixes.Core} Standard input closed while waiting for Release version. Aborting."
            assertEquals(warningCount(log, warning), 1)
          }
      }
    }
  }

  test("resolveVersions - fail when stdin closes before the next version prompt is answered") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      writeVersionFile(dir, """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n").flatMap {
        versionFile =>
          val buffered = bufferedLoadedState(
            dir,
            Seq(
              releaseIOVersioningFile           := versionFile,
              releaseIOVersioningReadVersion    := VersionSteps.defaultReadVersion,
              releaseIOVersioningFileContents   := VersionSteps.defaultWriteVersion(
                useGlobalVersion = true
              ),
              releaseIOVersioningUseGlobal      := true,
              releaseIOVersioningReleaseVersion := (_.stripSuffix("-SNAPSHOT")),
              releaseIOVersioningNextVersion    := (_ => "0.2.0-SNAPSHOT")
            )
          )

          for {
            _   <- TestSupport.withInput("1.0.0\n") {
                     TestAssertions.assertIllegalStateMessage(
                       VersionSteps
                         .resolveVersions(promptingContext(buffered.state), allowPrompts = true),
                       "Standard input closed while waiting for Next version."
                     )
                   }
            log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
          } yield {
            val warning =
              s"${ReleaseLogPrefixes.Core} Standard input closed while waiting for Next version. Aborting."
            assertEquals(warningCount(log, warning), 1)
          }
      }
    }
  }

  test("resolveVersions - fail when releaseIOVersioningReleaseVersion reports FailureCommand") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val marker = new File(dir, "release-version-task.marker")

      writeVersionFile(dir, """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n").flatMap {
        versionFile =>
          val state = TestSupport.loadedState(
            dir,
            Seq(
              Project("root", dir).settings(
                releaseIOVersioningFile         := versionFile,
                releaseIOVersioningReadVersion  := VersionSteps.defaultReadVersion,
                releaseIOVersioningFileContents := VersionSteps
                  .defaultWriteVersion(useGlobalVersion = true),
                releaseIOVersioningUseGlobal    := true,
                CoreStepTestCompat.failureCommandVersionTaskSetting(marker),
                releaseIOVersioningNextVersion  := (_ => "0.2.0-SNAPSHOT")
              )
            )
          )

          TestAssertions
            .assertFailure[IllegalStateException, (ReleaseContext, VersionSteps.ResolvedVersions)](
              VersionSteps.resolveVersions(promptingContext(state), allowPrompts = false)
            ) { err =>
              assert(marker.exists())
              assert(err.getMessage.contains(releaseIOVersioningReleaseVersion.key.label))
              assert(err.getMessage.contains("FailureCommand"))
            }
      }
    }
  }

  test("resolveVersions - fail when releaseIOVersioningNextVersion reports FailureCommand") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val marker = new File(dir, "next-version-task.marker")

      writeVersionFile(dir, """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n").flatMap {
        versionFile =>
          val state = TestSupport.loadedState(
            dir,
            Seq(
              Project("root", dir).settings(
                releaseIOVersioningFile           := versionFile,
                releaseIOVersioningReadVersion    := VersionSteps.defaultReadVersion,
                releaseIOVersioningFileContents   := VersionSteps
                  .defaultWriteVersion(useGlobalVersion = true),
                releaseIOVersioningUseGlobal      := true,
                releaseIOVersioningReleaseVersion := (_.stripSuffix("-SNAPSHOT")),
                CoreStepTestCompat.failureCommandNextVersionTaskSetting(marker)
              )
            )
          )

          TestAssertions
            .assertFailure[IllegalStateException, (ReleaseContext, VersionSteps.ResolvedVersions)](
              VersionSteps.resolveVersions(promptingContext(state), allowPrompts = false)
            ) { err =>
              assert(marker.exists())
              assert(err.getMessage.contains(releaseIOVersioningNextVersion.key.label))
              assert(err.getMessage.contains("FailureCommand"))
            }
      }
    }
  }

  test("commitReleaseVersion - expose the release hash through package options") {
    ReleaseTestSupport
      .gitRepoWithCommitResource(
        fixturePrefix,
        prepareRepo = repo =>
          IO.blocking(
            sbt.IO.write(
              new File(repo, "version.sbt"),
              """ThisBuild / version := "1.0.0-SNAPSHOT"""" + "\n"
            )
          )
      )
      .use { case (repo, vcs) =>
        val versionFile = new File(repo, "version.sbt")
        val state       = ReleaseTestSupport.gitRootState(
          repo,
          releaseManifestSettings() ++
            Seq(
              releaseIOVersioningFile          := versionFile,
              releaseIOVersioningReadVersion   := VersionSteps.defaultReadVersion,
              releaseIOVersioningFileContents  := VersionSteps.defaultWriteVersion(
                useGlobalVersion = true
              ),
              releaseIOVersioningUseGlobal     := true,
              releaseIOVcsReleaseCommitMessage := "Setting version to 1.0.0",
              releaseIOVcsSign                 := false,
              releaseIOVcsSignOff              := false
            )
        )
        val ctx         = ReleaseContext(state = state, vcs = Some(vcs))
          .withVersions("1.0.0", "1.0.1-SNAPSHOT")

        for {
          written  <- VersionSteps.setReleaseVersion.execute(ctx)
          result   <- VersionSteps.commitReleaseVersion.execute(written)
          headHash <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD")).map(_.trim)
        } yield {
          assert(manifestAttributes(result.state).contains("Vcs-Release-Hash" -> headHash))
        }
      }
  }

  test("commitReleaseVersion - finish staging and commit after cancellation is requested") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      writeVersionFile(dir, """ThisBuild / version := "1.0.0-SNAPSHOT"""" + "\n").flatMap {
        versionFile =>
          for {
            addStarted       <- Deferred[IO, Unit]
            allowAddToFinish <- Deferred[IO, Unit]
            commitCalls      <- Ref[IO].of(0)
            vcs               = cancelSafeCommitVcs(
                                  base = dir,
                                  addStarted = addStarted,
                                  allowAddToFinish = allowAddToFinish,
                                  commitCalls = commitCalls
                                )
            state             = TestSupport.loadedState(
                                  dir,
                                  Seq(
                                    Project("root", dir).settings(
                                      releaseIOVersioningFile          := versionFile,
                                      releaseIOVersioningReadVersion   := VersionSteps.defaultReadVersion,
                                      releaseIOVersioningFileContents  := VersionSteps.defaultWriteVersion(
                                        useGlobalVersion = true
                                      ),
                                      releaseIOVersioningUseGlobal     := true,
                                      releaseIOVcsReleaseCommitMessage := "Setting version to 1.0.0",
                                      releaseIOVcsSign                 := false,
                                      releaseIOVcsSignOff              := false
                                    )
                                  )
                                )
            ctx               = ReleaseContext(state = state, vcs = Some(vcs))
                                  .withVersions("1.0.0", "1.0.1-SNAPSHOT")
            written          <- VersionSteps.setReleaseVersion.execute(ctx)
            commitFiber      <- VersionSteps.commitReleaseVersion.execute(written).start
            _                <- addStarted.get
            cancelFiber      <- commitFiber.cancel.start
            _                <- allowAddToFinish.complete(()).void
            _                <- cancelFiber.join.void
            _                <- commitFiber.join.void
            calls            <- commitCalls.get
          } yield assertEquals(calls, 1)
      }
    }
  }

  test(
    "commitReleaseVersion - do not create a commit when releaseIOVcsReleaseCommitMessage reports FailureCommand"
  ) {
    ReleaseTestSupport
      .gitRepoWithCommitResource(
        fixturePrefix,
        prepareRepo = repo =>
          IO.blocking(
            sbt.IO.write(
              new File(repo, "version.sbt"),
              """ThisBuild / version := "1.0.0-SNAPSHOT"""" + "\n"
            )
          )
      )
      .use { case (repo, vcs) =>
        val marker      = new File(repo, "commit-message-task.marker")
        val versionFile = new File(repo, "version.sbt")
        val state       = ReleaseTestSupport.gitRootState(
          repo,
          Seq(
            releaseIOVersioningFile         := versionFile,
            releaseIOVersioningReadVersion  := VersionSteps.defaultReadVersion,
            releaseIOVersioningFileContents := VersionSteps.defaultWriteVersion(
              useGlobalVersion = true
            ),
            releaseIOVersioningUseGlobal    := true,
            CoreStepTestCompat.failureCommandCommitMessageSetting(marker),
            releaseIOVcsSign                := false,
            releaseIOVcsSignOff             := false
          )
        )
        val ctx         = ReleaseContext(state = state, vcs = Some(vcs))
          .withVersions("1.0.0", "1.0.1-SNAPSHOT")

        for {
          before  <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD")).map(_.trim)
          written <- VersionSteps.setReleaseVersion.execute(ctx)
          _       <- TestAssertions.assertFailure[IllegalStateException, ReleaseContext](
                       VersionSteps.commitReleaseVersion.execute(written)
                     ) { err =>
                       assert(marker.exists())
                       assert(err.getMessage.contains(releaseIOVcsReleaseCommitMessage.key.label))
                       assert(err.getMessage.contains("FailureCommand"))
                     }
          after   <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD")).map(_.trim)
        } yield {
          assertEquals(after, before)
        }
      }
  }

  test(
    "commitNextVersion - do not create a commit when releaseIOVcsNextCommitMessage reports FailureCommand"
  ) {
    ReleaseTestSupport
      .gitRepoWithCommitResource(
        fixturePrefix,
        prepareRepo = repo =>
          IO.blocking(
            sbt.IO.write(
              new File(repo, "version.sbt"),
              """ThisBuild / version := "1.0.0-SNAPSHOT"""" + "\n"
            )
          )
      )
      .use { case (repo, vcs) =>
        val marker      = new File(repo, "next-commit-message-task.marker")
        val versionFile = new File(repo, "version.sbt")
        val state       = ReleaseTestSupport.gitRootState(
          repo,
          Seq(
            releaseIOVersioningFile         := versionFile,
            releaseIOVersioningReadVersion  := VersionSteps.defaultReadVersion,
            releaseIOVersioningFileContents := VersionSteps.defaultWriteVersion(
              useGlobalVersion = true
            ),
            releaseIOVersioningUseGlobal    := true,
            CoreStepTestCompat.failureCommandNextCommitMessageSetting(marker),
            releaseIOVcsSign                := false,
            releaseIOVcsSignOff             := false
          )
        )
        val ctx         = ReleaseContext(state = state, vcs = Some(vcs))
          .withVersions("1.0.0", "1.0.1-SNAPSHOT")

        for {
          before  <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD")).map(_.trim)
          written <- VersionSteps.setNextVersion.execute(ctx)
          _       <- TestAssertions.assertFailure[IllegalStateException, ReleaseContext](
                       VersionSteps.commitNextVersion.execute(written)
                     ) { err =>
                       assert(marker.exists())
                       assert(err.getMessage.contains(releaseIOVcsNextCommitMessage.key.label))
                       assert(err.getMessage.contains("FailureCommand"))
                     }
          after   <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD")).map(_.trim)
        } yield {
          assertEquals(after, before)
        }
      }
  }

  private def assertReadVersion(dir: File, content: String, expected: String): IO[Unit] =
    writeVersionFile(dir, content).flatMap { file =>
      VersionSteps.defaultReadVersion(file).map(result => assertEquals(result, expected))
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

  private def withStartupPlan(
      ctx: ReleaseContext,
      releaseVersion: String,
      nextVersion: String
  ): ReleaseContext =
    ctx.withExecutionState(
      CoreExecutionState(
        CoreReleasePlan(
          flags = startupFlags,
          releaseVersionOverride = Some(releaseVersion),
          nextVersionOverride = Some(nextVersion),
          decisionDefaults = ReleaseDecisionDefaults.empty
        )
      )
    )

  private def promptingContext(state: sbt.State): ReleaseContext =
    ReleaseContext(state = state, interactive = true).withExecutionState(
      CoreExecutionState(
        CoreReleasePlan(
          flags = startupFlags.copy(interactive = true),
          releaseVersionOverride = None,
          nextVersionOverride = None,
          decisionDefaults = ReleaseDecisionDefaults.empty
        )
      )
    )

  private def bufferedLoadedState(
      dir: File,
      rootSettings: Seq[sbt.Setting[?]]
  ): TestSupport.BufferedState = {
    val buffered = TestSupport.bufferedState(dir)
    val state    = sbt.TestBuildState(
      baseState = buffered.state,
      baseDir = dir,
      projects = Seq(Project("root", dir).settings(rootSettings*)),
      currentProjectId = Some("root")
    )

    buffered.copy(state = state)
  }

  private def warningCount(log: String, warning: String): Int =
    log.sliding(warning.length).count(_ == warning)

  private def writeVersionFile(dir: File, content: String): IO[File] = {
    val file = new File(dir, "version.sbt")
    IO.blocking {
      sbt.IO.write(file, content)
      file
    }
  }

  private def cancelSafeCommitVcs(
      base: File,
      addStarted: Deferred[IO, Unit],
      allowAddToFinish: Deferred[IO, Unit],
      commitCalls: Ref[IO, Int]
  ): Vcs =
    new Vcs {
      override def baseDir: File                                                               = base
      override def commandName: String                                                         = "git"
      override def currentHash: IO[String]                                                     = IO.pure("deadbeef")
      override def currentBranch: IO[String]                                                   = IO.pure("main")
      override def trackingRemote: IO[String]                                                  = IO.pure("origin")
      override def upstreamTrackingHash: IO[Option[String]]                                    =
        IO.pure(Some("origin/main"))
      override def hasUpstream: IO[Boolean]                                                    = IO.pure(true)
      override def isBehindRemote: IO[Boolean]                                                 = IO.pure(false)
      override def existsTag(name: String): IO[Boolean]                                        = IO.pure(false)
      override def modifiedFiles: IO[Seq[String]]                                              = IO.pure(Seq("version.sbt"))
      override def stagedFiles: IO[Seq[String]]                                                = IO.pure(Nil)
      override def untrackedFiles: IO[Seq[String]]                                             = IO.pure(Nil)
      override def status: IO[String]                                                          = IO.pure("M version.sbt")
      override def checkRemote(remote: String): IO[Int]                                        = IO.pure(0)
      override def checkRemoteWithTimeout(
          remote: String,
          timeout: scala.concurrent.duration.FiniteDuration
      ): IO[Option[Int]]                                                                       =
        checkRemote(remote).map(Some(_))
      override def add(files: String*): IO[Unit]                                               =
        addStarted.complete(()).void *> allowAddToFinish.get
      override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit]          =
        commitCalls.update(_ + 1)
      override def tag(name: String, comment: String, sign: Boolean, force: Boolean): IO[Unit] =
        IO.unit
      override def pushChanges: IO[Unit]                                                       = IO.unit
    }
}
