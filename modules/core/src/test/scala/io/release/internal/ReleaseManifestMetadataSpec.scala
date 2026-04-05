package io.release.internal

import cats.effect.IO
import io.release.ReleaseIO
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
            ReleaseIO.releaseIOInternalReleaseHash := Some("abc123"),
            ReleaseIO.releaseIOInternalReleaseTag  := Some("v1.0.0")
          )
        )
        val cleared = ReleaseIO.clearReleaseManifestMetadata(seeded)

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

  test("clearReleaseManifestMetadata - allow a later release to replace cleared metadata") {
    releaseStateResource.use { baseState =>
      IO {
        val firstPass  = TestSupport.appendSessionSettings(
          baseState,
          Seq(
            ReleaseIO.releaseIOInternalReleaseHash := Some("first-hash"),
            ReleaseIO.releaseIOInternalReleaseTag  := Some("v1.0.0")
          )
        )
        val cleared    = ReleaseIO.clearReleaseManifestMetadata(firstPass)
        val secondPass = TestSupport.appendSessionSettings(
          cleared,
          Seq(
            ReleaseIO.releaseIOInternalReleaseHash := Some("second-hash"),
            ReleaseIO.releaseIOInternalReleaseTag  := Some("v2.0.0")
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
      fixtureNonce                           := nonce,
      packageOptions                         := {
        val _ = fixtureNonce.value
        basePackageOptions
      },
      ReleaseIO.releaseIOInternalReleaseHash := None,
      ReleaseIO.releaseIOInternalReleaseTag  := None,
      packageOptions ++= {
        val _ = fixtureNonce.value
        ReleaseIO.releaseManifestPackageOptions(
          ReleaseIO.releaseIOInternalReleaseHash.value,
          ReleaseIO.releaseIOInternalReleaseTag.value
        )
      }
    )
}
