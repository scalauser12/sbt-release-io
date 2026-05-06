package io.release.internal

import cats.effect.IO
import io.release.ReleaseManifestMetadata
import io.release.ReleaseManifestMetadata.releaseIOInternalReleaseHash
import io.release.ReleaseManifestMetadata.releaseIOInternalReleaseTag
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.Keys.packageOptions
import sbt.Package.ManifestAttributes
import sbt.Project
import sbt.settingKey

class ReleaseManifestMetadataSpec extends CatsEffectSuite {
  private val fixturePrefix = "release-manifest-metadata-spec"
  private val projectId     = "manifest-root"
  private val fixtureNonce  = settingKey[String]("Unique nonce for manifest metadata test tasks")

  test(
    "clearReleaseManifestMetadata - remove release-only manifest metadata and preserve other package options"
  ) {
    releaseStateResource.use { baseState =>
      IO {
        val seeded  = TestSupport.appendSessionSettings(
          baseState,
          Seq(
            releaseIOInternalReleaseHash := Some(
              "abc123"
            ),
            releaseIOInternalReleaseTag  := Some(
              "v1.0.0"
            )
          )
        )
        val cleared =
          ReleaseManifestMetadata.clearReleaseManifestMetadata(seeded)

        assertEquals(
          TestSupport.manifestAttributes(seeded),
          Set(
            "Existing"         -> "kept",
            "Vcs-Release-Hash" -> "abc123",
            "Vcs-Release-Tag"  -> "v1.0.0"
          )
        )
        assertEquals(TestSupport.manifestAttributes(cleared), Set("Existing" -> "kept"))
      }
    }
  }

  test(
    "clearReleaseManifestMetadata - cleared values stay cleared after a later structure rebuild"
  ) {
    releaseStateResource.use { baseState =>
      IO {
        val seeded  = TestSupport.appendSessionSettings(
          baseState,
          Seq(
            releaseIOInternalReleaseHash := Some("stale-hash"),
            releaseIOInternalReleaseTag  := Some("v0.0.0-stale")
          )
        )
        val cleared = ReleaseManifestMetadata.clearReleaseManifestMetadata(seeded)
        // Trigger a session-level structure rebuild that does NOT touch the
        // manifest keys. Pre-fix, this rebuilt mergeSettings = original ++
        // append ++ rawAppend(stale) and reintroduced the stale Some(...).
        val rebuilt =
          TestSupport.appendSessionSettings(cleared, Seq(fixtureNonce := "rebuilt"))

        assertEquals(TestSupport.manifestAttributes(rebuilt), Set("Existing" -> "kept"))
      }
    }
  }

  test("clearReleaseManifestMetadata - allow a later release to replace cleared metadata") {
    releaseStateResource.use { baseState =>
      IO {
        val firstPass  = TestSupport.appendSessionSettings(
          baseState,
          Seq(
            releaseIOInternalReleaseHash := Some(
              "first-hash"
            ),
            releaseIOInternalReleaseTag  := Some(
              "v1.0.0"
            )
          )
        )
        val cleared    =
          ReleaseManifestMetadata
            .clearReleaseManifestMetadata(firstPass)
        val secondPass = TestSupport.appendSessionSettings(
          cleared,
          Seq(
            releaseIOInternalReleaseHash := Some(
              "second-hash"
            ),
            releaseIOInternalReleaseTag  := Some(
              "v2.0.0"
            )
          )
        )

        assertEquals(
          TestSupport.manifestAttributes(secondPass),
          Set(
            "Existing"         -> "kept",
            "Vcs-Release-Hash" -> "second-hash",
            "Vcs-Release-Tag"  -> "v2.0.0"
          )
        )
      }
    }
  }

  private def releaseStateResource =
    TestSupport.tempDirResource(fixturePrefix).map { dir =>
      TestSupport.loadedState(
        dir,
        Seq(
          Project(projectId, dir).settings(
            (releaseManifestSettings(
              Seq(ManifestAttributes("Existing" -> "kept")),
              dir.getAbsolutePath
            ))*
          )
        ),
        currentProjectId = Some(projectId)
      )
    }

  private def releaseManifestSettings(
      basePackageOptions: Seq[sbt.PackageOption],
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
        ReleaseManifestMetadata.releaseManifestPackageOptions(
          releaseIOInternalReleaseHash.value,
          releaseIOInternalReleaseTag.value
        )
      }
    )
}
