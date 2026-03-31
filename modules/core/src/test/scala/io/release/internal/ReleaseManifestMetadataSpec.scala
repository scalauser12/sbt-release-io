package io.release.internal

import cats.effect.IO
import io.release.ReleaseIO
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.Keys.packageOptions
import sbt.Package.ManifestAttributes
import sbt.Project
import sbt.State

class ReleaseManifestMetadataSpec extends CatsEffectSuite {
  private val fixturePrefix = "release-manifest-metadata-spec"

  test(
    "clearReleaseManifestMetadata - remove release-only manifest metadata and preserve other package options"
  ) {
    releaseStateResource.use { baseState =>
      IO {
        val seeded  = TestSupport.appendSessionSettings(
          baseState,
          Seq(
            ReleaseIO.releaseIOInternalReleaseHash := Some("abc123"),
            ReleaseIO.releaseIOInternalReleaseTag := Some("v1.0.0")
          )
        )
        val cleared = ReleaseIO.clearReleaseManifestMetadata(seeded)

        assertEquals(
          manifestAttributes(seeded),
          Set(
            "Existing" -> "kept",
            "Vcs-Release-Hash" -> "abc123",
            "Vcs-Release-Tag" -> "v1.0.0"
          )
        )
        assertEquals(manifestAttributes(cleared), Set("Existing" -> "kept"))
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
            ReleaseIO.releaseIOInternalReleaseTag := Some("v1.0.0")
          )
        )
        val cleared    = ReleaseIO.clearReleaseManifestMetadata(firstPass)
        val secondPass = TestSupport.appendSessionSettings(
          cleared,
          Seq(
            ReleaseIO.releaseIOInternalReleaseHash := Some("second-hash"),
            ReleaseIO.releaseIOInternalReleaseTag := Some("v2.0.0")
          )
        )

        assertEquals(
          manifestAttributes(secondPass),
          Set(
            "Existing" -> "kept",
            "Vcs-Release-Hash" -> "second-hash",
            "Vcs-Release-Tag" -> "v2.0.0"
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
          Project("root", dir).settings(
            (releaseManifestSettings(Seq(ManifestAttributes("Existing" -> "kept"))))*
          )
        ),
        currentProjectId = Some("root")
      )
    }

  private def manifestAttributes(state: State): Set[(String, String)] = {
    val (_, options) = SbtRuntime.extracted(state).runTask(packageOptions, state)

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
      basePackageOptions: Seq[sbt.PackageOption]
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
