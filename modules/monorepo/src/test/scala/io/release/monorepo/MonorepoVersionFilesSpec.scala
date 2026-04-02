package io.release.monorepo

import cats.effect.IO
import munit.CatsEffectSuite

import java.io.File

class MonorepoVersionFilesSpec extends CatsEffectSuite {

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
              io.release.ReleaseIO.releaseIOVersioningFile      := rootVersionFile,
              MonorepoReleaseIO.releaseIOMonorepoVersioningFile := {
                (_: sbt.ProjectRef, _: sbt.State) =>
                  monorepoProjectVersion
              }
            )
          ),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              io.release.ReleaseIO.releaseIOVersioningFile := new File(
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
