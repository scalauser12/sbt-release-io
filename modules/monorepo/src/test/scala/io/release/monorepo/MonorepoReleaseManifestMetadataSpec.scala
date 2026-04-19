package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.ReleaseManifestMetadataSupport
import io.release.ReleaseManifestMetadataSupport.releaseIOInternalReleaseHash
import io.release.ReleaseManifestMetadataSupport.releaseIOInternalReleaseTag
import io.release.TestSupport
import io.release.monorepo.internal.steps.MonorepoVcsCommitHelpers
import io.release.monorepo.internal.steps.MonorepoVersionSteps
import io.release.runtime.sbt.SbtRuntime
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.Keys.packageOptions
import sbt.Keys.version
import sbt.Package.ManifestAttributes
import sbt.ProjectRef
import sbt.State
import sbt.settingKey

import java.io.File

class MonorepoReleaseManifestMetadataSpec extends CatsEffectSuite {
  private val fixturePrefix = "monorepo-release-manifest-metadata-spec"
  private val fixtureNonce  = settingKey[String]("Unique nonce for monorepo manifest metadata tests")

  test("commitVersions - add the release hash to each selected project's packageOptions") {
    gitFixtureResource.use { case (fixture, vcs) =>
      val ctx = fixture.context(
        selectedProjectIds = Seq("core", "api"),
        versionsById = Map(
          "core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"),
          "api"  -> ("2.0.0" -> "2.1.0-SNAPSHOT")
        ),
        vcs = Some(vcs)
      )

      for {
        _           <- writeVersion(new File(new File(fixture.dir, "core"), "version.sbt"), "1.0.0")
        _           <- writeVersion(new File(new File(fixture.dir, "api"), "version.sbt"), "2.0.0")
        result      <- MonorepoVcsCommitHelpers.commitVersions(
                         ctx,
                         MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsReleaseCommitMessage,
                         { case (releaseVer, _) => releaseVer },
                         persistReleaseHash = true
                       )
        currentHash <- vcs.currentHash
      } yield {
        assertEquals(
          TestSupport.manifestAttributes(result.state, fixture.refsById("core")),
          Set("Existing" -> "kept", "Vcs-Release-Hash" -> currentHash)
        )
        assertEquals(
          TestSupport.manifestAttributes(result.state, fixture.refsById("api")),
          Set("Existing" -> "kept", "Vcs-Release-Hash" -> currentHash)
        )
      }
    }
  }

  test("commitVersions - use the current HEAD hash when the release commit is empty") {
    gitFixtureResource.use { case (fixture, vcs) =>
      val ctx = fixture.context(
        selectedProjectIds = Seq("core", "api"),
        versionsById = Map(
          "core" -> ("0.1.0-SNAPSHOT" -> "0.2.0-SNAPSHOT"),
          "api"  -> ("0.1.0-SNAPSHOT" -> "0.2.0-SNAPSHOT")
        ),
        vcs = Some(vcs)
      )

      for {
        beforeHash <- vcs.currentHash
        result     <- MonorepoVcsCommitHelpers.commitVersions(
                        ctx,
                        MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsReleaseCommitMessage,
                        { case (releaseVer, _) => releaseVer },
                        persistReleaseHash = true
                      )
        afterHash  <- vcs.currentHash
      } yield {
        assertEquals(afterHash, beforeHash)
        assertEquals(
          TestSupport.manifestAttributes(result.state, fixture.refsById("core")),
          Set("Existing" -> "kept", "Vcs-Release-Hash" -> beforeHash)
        )
        assertEquals(
          TestSupport.manifestAttributes(result.state, fixture.refsById("api")),
          Set("Existing" -> "kept", "Vcs-Release-Hash" -> beforeHash)
        )
      }
    }
  }

  test("commitNextVersions - preserve the existing release hash metadata") {
    gitFixtureResource.use { case (fixture, vcs) =>
      val coreVersionFile = new File(new File(fixture.dir, "core"), "version.sbt")
      val apiVersionFile  = new File(new File(fixture.dir, "api"), "version.sbt")
      val ctx             = fixture.context(
        selectedProjectIds = Seq("core", "api"),
        versionsById = Map(
          "core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"),
          "api"  -> ("2.0.0" -> "2.1.0-SNAPSHOT")
        ),
        vcs = Some(vcs)
      )

      for {
        _                  <- writeVersion(coreVersionFile, "1.0.0")
        _                  <- writeVersion(apiVersionFile, "2.0.0")
        afterReleaseCommit <- MonorepoVersionSteps.commitReleaseVersions.execute(ctx)
        releaseHash        <- vcs.currentHash
        _                  <- writeVersion(coreVersionFile, "1.1.0-SNAPSHOT")
        _                  <- writeVersion(apiVersionFile, "2.1.0-SNAPSHOT")
        afterNextCommit    <- MonorepoVersionSteps.commitNextVersions.execute(afterReleaseCommit)
        nextHash           <- vcs.currentHash
      } yield {
        assertNotEquals(nextHash, releaseHash)
        assertEquals(
          TestSupport.manifestAttributes(afterNextCommit.state, fixture.refsById("core")),
          Set("Existing" -> "kept", "Vcs-Release-Hash" -> releaseHash)
        )
        assertEquals(
          TestSupport.manifestAttributes(afterNextCommit.state, fixture.refsById("api")),
          Set("Existing" -> "kept", "Vcs-Release-Hash" -> releaseHash)
        )
      }
    }
  }

  test(
    "commitReleaseVersions - preserve late-bound version settings for later next-version writes"
  ) {
    gitFixtureResource.use { case (fixture, vcs) =>
      val versionProperties = new File(new File(fixture.dir, "core"), "version.properties")
      val versionSbt        = new File(new File(fixture.dir, "core"), "version.sbt")
      val mutatedState      = TestSupport.appendSessionSettings(
        fixture.state,
        lateBoundVersionSettings(fixture.dir)
      )
      val ctx               = fixture
        .context(
          selectedProjectIds = Seq("core"),
          versionsById = Map(
            "core" -> ("1.0.0" -> "1.1.0-SNAPSHOT")
          ),
          vcs = Some(vcs)
        )
        .withState(mutatedState)
        .withProjects(
          Seq(fixture.projectInfo("core", versions = Some("1.0.0" -> "1.1.0-SNAPSHOT")))
            .map(_.copy(versionFile = versionProperties))
        )

      for {
        _           <- IO.blocking(sbt.IO.write(versionProperties, "version=0.1.0-SNAPSHOT\n"))
        project      = MonorepoSpecSupport.projectNamed(ctx.projects, "core")
        afterWrite  <- MonorepoVersionSteps.setReleaseVersions.execute(ctx, project)
        afterCommit <- MonorepoVersionSteps.commitReleaseVersions.execute(afterWrite)
        nextWrite   <- MonorepoVersionSteps.setNextVersions.execute(
                         afterCommit,
                         MonorepoSpecSupport.projectNamed(afterCommit.projects, "core")
                       )
      } yield {
        assertEquals(sbt.IO.read(versionProperties), "version=1.1.0-SNAPSHOT\n")
        assertEquals(sbt.IO.read(versionSbt), """version := "0.1.0-SNAPSHOT"""" + "\n")
        assertEquals(
          MonorepoSpecSupport.projectNamed(nextWrite.projects, "core").versionFile,
          versionProperties
        )
      }
    }
  }

  test("commitReleaseVersions - stage the version file captured during the write step") {
    gitFixtureResource.use { case (fixture, vcs) =>
      val coreVersionFile      = new File(new File(fixture.dir, "core"), "version.sbt")
      val alternateVersionFile = new File(new File(fixture.dir, "core"), "alternate-version.sbt")
      val mutatedState         = SbtRuntime.appendWithSession(
        fixture.state,
        Seq(
          MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
            (ref: ProjectRef, state: State) =>
              val resolvedVersion = SbtRuntime.extracted(state).getOpt(ref / version).getOrElse("")
              val defaultFile     = new File(new File(fixture.dir, ref.project), "version.sbt")
              if (ref.project == "core" && resolvedVersion == "1.0.0") alternateVersionFile
              else defaultFile
          }
        )
      )
      val ctx                  = fixture
        .context(
          selectedProjectIds = Seq("core"),
          versionsById = Map(
            "core" -> ("1.0.0" -> "1.1.0-SNAPSHOT")
          ),
          vcs = Some(vcs)
        )
        .withState(mutatedState)
      val project              = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

      for {
        _           <- writeVersion(coreVersionFile, "0.1.0-SNAPSHOT")
        _           <- writeVersion(alternateVersionFile, "0.1.0-SNAPSHOT")
        _           <- IO.blocking {
                         TestSupport.runGit(fixture.dir, "add", "core/alternate-version.sbt")
                         TestSupport.runGit(fixture.dir, "commit", "-m", "Add alternate version file")
                       }
        afterWrite  <- MonorepoVersionSteps.setReleaseVersions.execute(ctx, project)
        afterCommit <- MonorepoVersionSteps.commitReleaseVersions.execute(afterWrite)
        status      <- IO.blocking(TestSupport.runGit(fixture.dir, "status", "--short"))
        headVersion <- IO.blocking(TestSupport.runGit(fixture.dir, "show", "HEAD:core/version.sbt"))
        headAlt     <-
          IO.blocking(TestSupport.runGit(fixture.dir, "show", "HEAD:core/alternate-version.sbt"))
      } yield {
        assertEquals(
          MonorepoSpecSupport.projectNamed(afterWrite.projects, "core").versionFile,
          coreVersionFile
        )
        assertEquals(
          MonorepoSpecSupport.projectNamed(afterCommit.projects, "core").versionFile,
          coreVersionFile
        )
        assertEquals(headVersion.trim, """version := "1.0.0"""")
        assertEquals(headAlt.trim, """version := "0.1.0-SNAPSHOT"""")
        assertEquals(status.trim, "")
      }
    }
  }

  test("commitVersions - stage all selected version files in a single add call") {
    fixtureResource.use { fixture =>
      Ref.of[IO, Vector[List[String]]](Vector.empty).flatMap { addCalls =>
        val vcs = new RecordingVcs(fixture.dir, addCalls)
        val ctx = fixture.context(
          selectedProjectIds = Seq("core", "api"),
          versionsById = Map(
            "core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"),
            "api"  -> ("2.0.0" -> "2.1.0-SNAPSHOT")
          ),
          vcs = Some(vcs)
        )

        MonorepoVcsCommitHelpers
          .commitVersions(
            ctx,
            MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsReleaseCommitMessage,
            { case (releaseVer, _) => releaseVer },
            persistReleaseHash = false
          )
          .flatMap(_ => addCalls.get)
          .map(seen => assertEquals(seen, Vector(List("core/version.sbt", "api/version.sbt"))))
      }
    }
  }

  test("commitVersions - skip staging when there are no selected version files") {
    fixtureResource.use { fixture =>
      Ref.of[IO, Vector[List[String]]](Vector.empty).flatMap { addCalls =>
        val vcs = new RecordingVcs(fixture.dir, addCalls)
        val ctx = fixture.context(
          selectedProjectIds = Nil,
          versionsById = Map.empty,
          vcs = Some(vcs)
        )

        MonorepoVcsCommitHelpers
          .commitVersions(
            ctx,
            MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsReleaseCommitMessage,
            { case (releaseVer, _) => releaseVer },
            persistReleaseHash = false
          )
          .flatMap { result =>
            addCalls.get.map { seen =>
              assertEquals(result.currentProjects, Nil)
              assertEquals(seen, Vector.empty)
            }
          }
      }
    }
  }

  test(
    "clearReleaseManifestMetadata - remove project-scoped metadata and preserve unrelated package options"
  ) {
    fixtureResource.use { fixture =>
      IO {
        val coreRef = fixture.refsById("core")
        val apiRef  = fixture.refsById("api")
        val seeded  = TestSupport.appendSessionSettings(
          fixture.state,
          ReleaseManifestMetadataSupport
            .releaseManifestHashSettings(Seq(coreRef, apiRef), "abc123") ++
            ReleaseManifestMetadataSupport
              .releaseManifestTagSettings(coreRef, "core/v1.0.0") ++
            ReleaseManifestMetadataSupport
              .releaseManifestTagSettings(apiRef, "api/v2.0.0")
        )
        val cleared = ReleaseManifestMetadataSupport.clearReleaseManifestMetadata(
          seeded,
          fixture.refsById.values.toSeq
        )

        assertEquals(
          TestSupport.manifestAttributes(seeded, coreRef),
          Set(
            "Existing"         -> "kept",
            "Vcs-Release-Hash" -> "abc123",
            "Vcs-Release-Tag"  -> "core/v1.0.0"
          )
        )
        assertEquals(
          TestSupport.manifestAttributes(seeded, apiRef),
          Set(
            "Existing"         -> "kept",
            "Vcs-Release-Hash" -> "abc123",
            "Vcs-Release-Tag"  -> "api/v2.0.0"
          )
        )
        assertEquals(TestSupport.manifestAttributes(cleared, coreRef), Set("Existing" -> "kept"))
        assertEquals(TestSupport.manifestAttributes(cleared, apiRef), Set("Existing" -> "kept"))
      }
    }
  }

  test("clearReleaseManifestMetadata - allow later releases to replace cleared metadata") {
    fixtureResource.use { fixture =>
      IO {
        val coreRef    = fixture.refsById("core")
        val apiRef     = fixture.refsById("api")
        val firstPass  = TestSupport.appendSessionSettings(
          fixture.state,
          ReleaseManifestMetadataSupport
            .releaseManifestHashSettings(Seq(coreRef, apiRef), "first-hash") ++
            ReleaseManifestMetadataSupport
              .releaseManifestTagSettings(coreRef, "core/v1.0.0") ++
            ReleaseManifestMetadataSupport
              .releaseManifestTagSettings(apiRef, "api/v1.0.0")
        )
        val cleared    = ReleaseManifestMetadataSupport.clearReleaseManifestMetadata(
          firstPass,
          fixture.refsById.values.toSeq
        )
        val secondPass = TestSupport.appendSessionSettings(
          cleared,
          ReleaseManifestMetadataSupport
            .releaseManifestHashSettings(Seq(coreRef, apiRef), "second-hash") ++
            ReleaseManifestMetadataSupport
              .releaseManifestTagSettings(coreRef, "core/v2.0.0") ++
            ReleaseManifestMetadataSupport
              .releaseManifestTagSettings(apiRef, "api/v2.0.0")
        )

        assertEquals(
          TestSupport.manifestAttributes(secondPass, coreRef),
          Set(
            "Existing"         -> "kept",
            "Vcs-Release-Hash" -> "second-hash",
            "Vcs-Release-Tag"  -> "core/v2.0.0"
          )
        )
        assertEquals(
          TestSupport.manifestAttributes(secondPass, apiRef),
          Set(
            "Existing"         -> "kept",
            "Vcs-Release-Hash" -> "second-hash",
            "Vcs-Release-Tag"  -> "api/v2.0.0"
          )
        )
      }
    }
  }

  private def fixtureResource: Resource[IO, MonorepoSpecSupport.LoadedFixture] =
    MonorepoSpecSupport.loadedFixtureResource(fixturePrefix) { dir =>
      val coreBase = new File(dir, "core")
      val apiBase  = new File(dir, "api")
      coreBase.mkdirs()
      apiBase.mkdirs()
      sbt.IO.write(new File(coreBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
      sbt.IO.write(new File(apiBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")

      Seq(
        MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core", "api")),
        MonorepoSpecSupport.versionedProject(
          "core",
          coreBase,
          settings = releaseManifestSettings(nonce = coreBase.getAbsolutePath)
        ),
        MonorepoSpecSupport.versionedProject(
          "api",
          apiBase,
          settings = releaseManifestSettings(nonce = apiBase.getAbsolutePath)
        )
      )
    }

  private def gitFixtureResource: Resource[IO, (MonorepoSpecSupport.LoadedFixture, Vcs)] =
    fixtureResource.evalMap { fixture =>
      IO.blocking {
        TestSupport.initGitRepo(fixture.dir)
        TestSupport.commitAll(fixture.dir, "Initial commit")
      } *> Vcs.detect(fixture.dir).flatMap {
        case Some(vcs) => IO.pure(fixture -> vcs)
        case None      =>
          IO.raiseError(
            new RuntimeException(s"Failed to detect VCS in ${fixture.dir.getAbsolutePath}")
          )
      }
    }

  private def writeVersion(file: File, version: String): IO[Unit] =
    IO.blocking(sbt.IO.write(file, s"""version := "$version"\n"""))

  private def lateBoundVersionSettings(repo: File): Seq[sbt.Setting[?]] =
    Seq(
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile         := {
        (ref: ProjectRef, _: State) =>
          new File(new File(repo, ref.project), "version.properties")
      },
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningReadVersion  := { file =>
        IO.blocking(sbt.IO.read(file).trim.stripPrefix("version="))
      },
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFileContents := { (_, version) =>
        IO.pure(s"version=$version\n")
      }
    )

  private def releaseManifestSettings(
      basePackageOptions: Seq[sbt.PackageOption] = Seq(ManifestAttributes("Existing" -> "kept")),
      nonce: String
  ): Seq[sbt.Setting[?]] =
    Seq(
      fixtureNonce                 := nonce,
      packageOptions               := {
        val _ = fixtureNonce.value
        basePackageOptions
      },
      releaseIOInternalReleaseHash := None,
      releaseIOInternalReleaseTag  := None,
      packageOptions ++= {
        val _ = fixtureNonce.value
        ReleaseManifestMetadataSupport.releaseManifestPackageOptions(
          releaseIOInternalReleaseHash.value,
          releaseIOInternalReleaseTag.value
        )
      }
    )
}

private final class RecordingVcs(
    override val baseDir: File,
    addCalls: Ref[IO, Vector[List[String]]]
) extends Vcs {
  override def commandName: String = "git"

  override def currentHash: IO[String] = IO.pure("abc123")

  override def currentBranch: IO[String] = IO.pure("main")

  override def trackingRemote: IO[String] = IO.pure("origin")

  override def upstreamTrackingHash: IO[Option[String]] = IO.pure(Some("origin/main"))

  override def hasUpstream: IO[Boolean] = IO.pure(true)

  override def isBehindRemote: IO[Boolean] = IO.pure(false)

  override def existsTag(name: String): IO[Boolean] = IO.pure(false)

  override def tagCommitHash(name: String): IO[Option[String]] = IO.pure(None)

  override def modifiedFiles: IO[Seq[String]] = IO.pure(Nil)

  override def stagedFiles: IO[Seq[String]] = IO.pure(Nil)

  override def untrackedFiles: IO[Seq[String]] = IO.pure(Nil)

  override def status: IO[String] = IO.pure("")

  override def checkRemote(remote: String): IO[Int] = IO.pure(0)

  override def checkRemoteWithTimeout(
      remote: String,
      timeout: scala.concurrent.duration.FiniteDuration
  ): IO[Option[Int]] =
    checkRemote(remote).map(Some(_))

  override def add(files: String*): IO[Unit] =
    addCalls.update(_ :+ files.toList)

  override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit] =
    IO.unit

  override def tag(name: String, comment: String, sign: Boolean, force: Boolean): IO[Unit] =
    IO.unit

  override def pushChanges: IO[Unit] = IO.unit
}
