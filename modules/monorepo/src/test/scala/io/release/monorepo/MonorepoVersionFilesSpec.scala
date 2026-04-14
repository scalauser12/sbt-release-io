package io.release.monorepo

import cats.effect.IO
import io.release.ReleasePluginIO
import io.release.monorepo.internal.*
import munit.CatsEffectSuite

import java.io.File

class MonorepoVersionFilesSpec extends CatsEffectSuite {

  test("resolve - honor ThisBuild releaseIOVersioningFile when project scope is unset") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-files-thisbuild") { dir =>
        val coreBase        = new File(dir, "core")
        val rootVersionFile = new File(dir, "root-version.sbt")
        coreBase.mkdirs()
        sbt.IO.write(rootVersionFile, """version := "root-only"""" + "\n")

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core"),
            settings = Seq(
              sbt.ThisBuild / ReleasePluginIO.autoImport.releaseIOVersioningFile := rootVersionFile
            )
          ),
          sbt.Project("core", coreBase)
        )
      }
      .use { loaded =>
        IO {
          val resolved = MonorepoVersionFiles.resolve(loaded.state, loaded.projectInfo("core").ref)
          assertEquals(resolved, new File(loaded.dir, "root-version.sbt"))
          assertNotEquals(resolved, new File(new File(loaded.dir, "core"), "version.sbt"))
        }
      }
  }

  test(
    "resolve - use the monorepo per-project resolver instead of the root releaseIOVersioningFile"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-files") { dir =>
        val coreBase               = new File(dir, "core")
        val rootVersionFile        = new File(dir, "root-version.sbt")
        val monorepoProjectVersion = new File(coreBase, "core-version.properties")
        coreBase.mkdirs()
        sbt.IO.write(rootVersionFile, """version := "root-only"""" + "\n")
        sbt.IO.write(monorepoProjectVersion, "version=0.1.0-SNAPSHOT\n")

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core"),
            settings = Seq(
              ReleasePluginIO.autoImport.releaseIOVersioningFile               := rootVersionFile,
              MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
                (_: sbt.ProjectRef, _: sbt.State) =>
                  monorepoProjectVersion
              }
            )
          ),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              ReleasePluginIO.autoImport.releaseIOVersioningFile := new File(
                coreBase,
                "ignored-version.sbt"
              )
            )
          )
        )
      }
      .use { loaded =>
        IO {
          val resolved = MonorepoVersionFiles.resolve(loaded.state, loaded.projectInfo("core").ref)
          assertEquals(resolved, new File(new File(loaded.dir, "core"), "core-version.properties"))
          assertNotEquals(resolved, new File(loaded.dir, "root-version.sbt"))
        }
      }
  }
}
