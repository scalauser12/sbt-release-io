package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseIO
import io.release.TestSupport
import io.release.internal.SbtRuntime
import io.release.monorepo.steps.MonorepoVcsCommitHelpers
import io.release.monorepo.steps.MonorepoVersionSteps
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.Keys.{packageOptions, version}
import sbt.Package.ManifestAttributes
import sbt.ProjectRef
import sbt.State

import java.io.File

class MonorepoReleaseManifestMetadataSpec extends CatsEffectSuite {
  private val fixturePrefix = "monorepo-release-manifest-metadata-spec"

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
        _          <- writeVersion(new File(new File(fixture.dir, "core"), "version.sbt"), "1.0.0")
        _          <- writeVersion(new File(new File(fixture.dir, "api"), "version.sbt"), "2.0.0")
        result     <- MonorepoVcsCommitHelpers.commitVersions(
                        ctx,
                        MonorepoReleaseIO.releaseIOMonorepoCommitMessage,
                        { case (releaseVer, _) => releaseVer },
                        persistReleaseHash = true
                      )
        currentHash <- vcs.currentHash
      } yield {
        assertEquals(
          manifestAttributes(result.state, fixture.refsById("core")),
          Set("Existing" -> "kept", "Vcs-Release-Hash" -> currentHash)
        )
        assertEquals(
          manifestAttributes(result.state, fixture.refsById("api")),
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
                        MonorepoReleaseIO.releaseIOMonorepoCommitMessage,
                        { case (releaseVer, _) => releaseVer },
                        persistReleaseHash = true
                      )
        afterHash  <- vcs.currentHash
      } yield {
        assertEquals(afterHash, beforeHash)
        assertEquals(
          manifestAttributes(result.state, fixture.refsById("core")),
          Set("Existing" -> "kept", "Vcs-Release-Hash" -> beforeHash)
        )
        assertEquals(
          manifestAttributes(result.state, fixture.refsById("api")),
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
          manifestAttributes(afterNextCommit.state, fixture.refsById("core")),
          Set("Existing" -> "kept", "Vcs-Release-Hash" -> releaseHash)
        )
        assertEquals(
          manifestAttributes(afterNextCommit.state, fixture.refsById("api")),
          Set("Existing" -> "kept", "Vcs-Release-Hash" -> releaseHash)
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
          MonorepoReleaseIO.releaseIOMonorepoVersionFile := {
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

  test(
    "clearReleaseManifestMetadata - remove project-scoped metadata and preserve unrelated package options"
  ) {
    fixtureResource.use { fixture =>
      IO {
        val coreRef  = fixture.refsById("core")
        val apiRef   = fixture.refsById("api")
        val seeded   = TestSupport.appendSessionSettings(
          fixture.state,
          ReleaseIO.releaseManifestHashSettings(Seq(coreRef, apiRef), "abc123") ++
            ReleaseIO.releaseManifestTagSettings(coreRef, "core/v1.0.0") ++
            ReleaseIO.releaseManifestTagSettings(apiRef, "api/v2.0.0")
        )
        val cleared  = ReleaseIO.clearReleaseManifestMetadata(
          seeded,
          fixture.refsById.values.toSeq
        )

        assertEquals(
          manifestAttributes(seeded, coreRef),
          Set(
            "Existing" -> "kept",
            "Vcs-Release-Hash" -> "abc123",
            "Vcs-Release-Tag" -> "core/v1.0.0"
          )
        )
        assertEquals(
          manifestAttributes(seeded, apiRef),
          Set(
            "Existing" -> "kept",
            "Vcs-Release-Hash" -> "abc123",
            "Vcs-Release-Tag" -> "api/v2.0.0"
          )
        )
        assertEquals(manifestAttributes(cleared, coreRef), Set("Existing" -> "kept"))
        assertEquals(manifestAttributes(cleared, apiRef), Set("Existing" -> "kept"))
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
          ReleaseIO.releaseManifestHashSettings(Seq(coreRef, apiRef), "first-hash") ++
            ReleaseIO.releaseManifestTagSettings(coreRef, "core/v1.0.0") ++
            ReleaseIO.releaseManifestTagSettings(apiRef, "api/v1.0.0")
        )
        val cleared    = ReleaseIO.clearReleaseManifestMetadata(
          firstPass,
          fixture.refsById.values.toSeq
        )
        val secondPass = TestSupport.appendSessionSettings(
          cleared,
          ReleaseIO.releaseManifestHashSettings(Seq(coreRef, apiRef), "second-hash") ++
            ReleaseIO.releaseManifestTagSettings(coreRef, "core/v2.0.0") ++
            ReleaseIO.releaseManifestTagSettings(apiRef, "api/v2.0.0")
        )

        assertEquals(
          manifestAttributes(secondPass, coreRef),
          Set(
            "Existing" -> "kept",
            "Vcs-Release-Hash" -> "second-hash",
            "Vcs-Release-Tag" -> "core/v2.0.0"
          )
        )
        assertEquals(
          manifestAttributes(secondPass, apiRef),
          Set(
            "Existing" -> "kept",
            "Vcs-Release-Hash" -> "second-hash",
            "Vcs-Release-Tag" -> "api/v2.0.0"
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
          settings = releaseManifestSettings()
        ),
        MonorepoSpecSupport.versionedProject(
          "api",
          apiBase,
          settings = releaseManifestSettings()
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

  private def manifestAttributes(
      state: State,
      projectRef: ProjectRef
  ): Set[(String, String)] = {
    val (_, options) = SbtRuntime.extracted(state).runTask(projectRef / packageOptions, state)

    options.flatMap {
      case product: Product if product.productPrefix == "ManifestAttributes" =>
        product.productElement(0) match {
          case entries: Seq[?] @unchecked =>
            entries.collect { case (name, value: String) =>
              name.toString -> value
            }
          case _                         => Seq.empty
        }
      case _                                                       => Seq.empty
    }.toSet
  }

  private def releaseManifestSettings(
      basePackageOptions: Seq[sbt.PackageOption] = Seq(ManifestAttributes("Existing" -> "kept"))
  ): Seq[sbt.Setting[?]] =
    Seq(
      packageOptions                         := basePackageOptions,
      ReleaseIO.releaseIOInternalReleaseHash := None,
      ReleaseIO.releaseIOInternalReleaseTag  := None,
      packageOptions ++= ReleaseIO.releaseManifestPackageOptions(
        ReleaseIO.releaseIOInternalReleaseHash.value,
        ReleaseIO.releaseIOInternalReleaseTag.value
      )
    )
}
