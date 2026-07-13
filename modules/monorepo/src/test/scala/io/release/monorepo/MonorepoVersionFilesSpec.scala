package io.release.monorepo

import cats.effect.IO
import io.release.ReleasePluginIO
import io.release.monorepo.internal.*
import io.release.runtime.sbt.SbtRuntime
import munit.CatsEffectSuite
import sbt.*

import java.io.File

class MonorepoVersionFilesSpec extends CatsEffectSuite {

  private def resolve(state: State, ref: ProjectRef): File =
    MonorepoVersionFiles.resolve(MonorepoRuntime.fromState(state), ref)

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
          val resolved = resolve(loaded.state, loaded.projectInfo("core").ref)
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
          val resolved = resolve(loaded.state, loaded.projectInfo("core").ref)
          assertEquals(resolved, new File(new File(loaded.dir, "core"), "core-version.properties"))
          assertNotEquals(resolved, new File(loaded.dir, "root-version.sbt"))
        }
      }
  }

  test(
    "liftLateBoundVersioningSettings promotes dependent definitions once and keeps dependencies live"
  ) {
    val helperDirectory = settingKey[File]("transient version resolver helper directory")
    val unrelated       = settingKey[String]("unrelated transient setting")

    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-files-late-bound") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()
        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject("core", coreBase)
        )
      }
      .use { loaded =>
        IO.blocking {
          val firstDirectory  = new File(loaded.dir, "first")
          val secondDirectory = new File(loaded.dir, "second")
          val transient       = SbtRuntime.appendWithSession(
            loaded.state,
            Seq(
              ThisBuild / helperDirectory                                                  := firstDirectory,
              ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
                // Deliberately use a relative project-scoped reference while the helper
                // is defined at ThisBuild, exercising sbt's scope-delegation rules.
                val directory = helperDirectory.value
                (_: ProjectRef, _: State) => new File(directory, "version.sbt")
              },
              unrelated                                                                    := "drop-me"
            )
          )

          val lifted      = MonorepoVersionFiles.liftLateBoundVersioningSettings(transient)
          val liftedAgain = MonorepoVersionFiles.liftLateBoundVersioningSettings(lifted)
          assert(
            lifted eq liftedAgain,
            "a second lift must return the identical State when no transient roots remain"
          )

          val helperOverride = SbtRuntime.appendWithSession(
            lifted,
            Seq(ThisBuild / helperDirectory := secondDirectory)
          )
          val relifted       = MonorepoVersionFiles.liftLateBoundVersioningSettings(helperOverride)
          val rebuilt        = SbtRuntime.appendSessionSettings(
            relifted,
            Seq(settingKey[String]("force a later session rebuild") := "rebuilt")
          )
          val resolved       = resolve(rebuilt, loaded.projectInfo("core").ref)

          assertEquals(resolved, new File(secondDirectory, "version.sbt"))
          assertEquals(Project.extract(rebuilt).getOpt(unrelated), None)
        }
      }
  }

  test(
    "liftLateBoundVersioningSettings ignores dependencies of shadowed definitive definitions"
  ) {
    val shadowedHelper = settingKey[File]("shadowed transient version resolver helper")
    val activeHelper   = settingKey[File]("active transient version resolver helper")

    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-files-shadowed-dependency") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()
        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject("core", coreBase)
        )
      }
      .use { loaded =>
        IO.blocking {
          val shadowedDirectory = new File(loaded.dir, "shadowed")
          val activeDirectory   = new File(loaded.dir, "active")
          val transient         = SbtRuntime.appendWithSession(
            loaded.state,
            Seq(
              ThisBuild / shadowedHelper                                                   :=
                shadowedDirectory,
              ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
                val directory = (ThisBuild / shadowedHelper).value
                (_: ProjectRef, _: State) => new File(directory, "version.sbt")
              },
              ThisBuild / activeHelper                                                     :=
                activeDirectory,
              ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
                val directory = (ThisBuild / activeHelper).value
                (_: ProjectRef, _: State) => new File(directory, "version.sbt")
              }
            )
          )

          val lifted                      = MonorepoVersionFiles.liftLateBoundVersioningSettings(transient)
          val promotedResolverDefinitions = Project
            .extract(lifted)
            .session
            .rawAppend
            .count(
              _.key.key == MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile.key
            )
          val rebuilt                     = SbtRuntime.appendSessionSettings(
            lifted,
            Seq(settingKey[String]("force a shadowed-dependency rebuild") := "rebuilt")
          )
          val extracted                   = Project.extract(rebuilt)
          val resolved                    = resolve(rebuilt, loaded.projectInfo("core").ref)

          assertEquals(promotedResolverDefinitions, 1)
          assertEquals(resolved, new File(activeDirectory, "version.sbt"))
          assertEquals(extracted.getOpt(ThisBuild / activeHelper), Some(activeDirectory))
          assertEquals(extracted.getOpt(ThisBuild / shadowedHelper), None)
        }
      }
  }

  test(
    "liftLateBoundVersioningSettings promotes the delegated base of a nondefinitive dependency"
  ) {
    val helperParts = settingKey[Seq[String]]("delegated transient resolver helper parts")

    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-files-nondefinitive-dependency") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()
        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject("core", coreBase)
        )
      }
      .use { loaded =>
        IO.blocking {
          val rootRef   = Project.extract(loaded.state).currentRef
          val transient = SbtRuntime.appendWithSession(
            loaded.state,
            Seq(
              ThisBuild / helperParts                                                    := Seq("delegated"),
              rootRef / helperParts += "updated",
              rootRef / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
                val parts = (rootRef / helperParts).value
                (_: ProjectRef, _: State) =>
                  new File(loaded.dir, s"${parts.mkString("-")}/version.sbt")
              }
            )
          )

          val lifted    = MonorepoVersionFiles.liftLateBoundVersioningSettings(transient)
          val rebuilt   = SbtRuntime.appendSessionSettings(
            lifted,
            Seq(settingKey[String]("force a delegated-base rebuild") := "rebuilt")
          )
          val extracted = Project.extract(rebuilt)
          val resolved  = resolve(rebuilt, loaded.projectInfo("core").ref)

          assertEquals(extracted.get(rootRef / helperParts), Seq("delegated", "updated"))
          assertEquals(
            extracted.session.rawAppend.count(_.key.key == helperParts.key),
            2
          )
          assertEquals(
            resolved,
            new File(loaded.dir, "delegated-updated/version.sbt")
          )
        }
      }
  }

  test("liftLateBoundVersioningSettings accepts an empty appendWithSession rebuild") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-files-empty-transient-append") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()
        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject("core", coreBase)
        )
      }
      .use { loaded =>
        IO.blocking {
          val persistentDirectory = new File(loaded.dir, "persistent")
          val persistent          = SbtRuntime.appendSessionSettings(
            loaded.state,
            Seq(
              ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
                (_: ProjectRef, _: State) => new File(persistentDirectory, "version.sbt")
              }
            )
          )
          val before              = Project.extract(persistent)
          val rebuilt             = before.appendWithSession(Seq.empty, persistent)
          val after               = Project.extract(rebuilt)
          val lifted              = MonorepoVersionFiles.liftLateBoundVersioningSettings(rebuilt)
          val resolved            =
            resolve(lifted, loaded.projectInfo("core").ref)

          assert(before.session eq after.session)
          assert(!(before.structure eq after.structure))
          assertEquals(after.structure.settings.length, before.structure.settings.length)
          assert(lifted eq rebuilt, "an empty rebuild must not create a transient suffix")
          assertEquals(resolved, new File(persistentDirectory, "version.sbt"))
        }
      }
  }

  test(
    "liftLateBoundVersioningSettings rejects a guard-preserving same-key equal-size appendWithoutSession replacement"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-files-invalid-prefix") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()
        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject("core", coreBase)
        )
      }
      .use { loaded =>
        IO.blocking {
          val persistentDirectory  = new File(loaded.dir, "persistent")
          val replacementDirectory = new File(loaded.dir, "replacement")
          val persistent           = SbtRuntime.appendSessionSettings(
            loaded.state,
            Seq(
              ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
                (_: ProjectRef, _: State) => new File(persistentDirectory, "version.sbt")
              }
            )
          )
          val persistentExtracted  = Project.extract(persistent)
          val resolverKey          =
            MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile.key
          val persistentRawAppend  = persistentExtracted.session.rawAppend
          val resolverDefinitions  = persistentRawAppend.filter(_.key.key == resolverKey)
          val guardDefinitions     = persistentRawAppend.filterNot(_.key.key == resolverKey)
          assertEquals(resolverDefinitions.length, 1)
          assertEquals(guardDefinitions.length, 1)

          val replacementResolver: (ProjectRef, State) => File =
            (_: ProjectRef, _: State) => new File(replacementDirectory, "version.sbt")
          val replacementSettings                              = persistentRawAppend.map { definition =>
            if (definition.key.key == resolverKey)
              Def
                .setting(
                  definition.key.asInstanceOf[Def.ScopedKey[(ProjectRef, State) => File]],
                  Def.valueStrict(replacementResolver)
                )
                .withPos(definition.pos)
            else definition
          }
          assertEquals(
            replacementSettings.map(_.key),
            persistentRawAppend.map(_.key)
          )
          assertEquals(
            replacementSettings.map(_.positionString),
            persistentRawAppend.map(_.positionString)
          )
          val provenanceKeyLabel                               =
            "releaseIOInternalTrustedSessionStructureGuard"
          assert(
            resolverDefinitions.head.dependencies.exists(_.key.label == provenanceKeyLabel),
            "the persistent resolver must carry private definition provenance"
          )
          assert(
            !replacementSettings
              .filter(_.key.key == resolverKey)
              .exists(_.dependencies.exists(_.key.label == provenanceKeyLabel)),
            "a fresh same-key replacement must not inherit private definition provenance"
          )
          assert(
            replacementSettings
              .zip(persistentRawAppend)
              .forall { case (replacement, original) =>
                replacement.key.key == resolverKey ||
                (replacement.asInstanceOf[AnyRef] eq original.asInstanceOf[AnyRef])
              },
            "non-resolver rawAppend definitions, including the private guard, must be preserved"
          )
          val replaced                                         = persistentExtracted.appendWithoutSession(
            replacementSettings,
            persistent
          )
          val replacedExtracted                                = Project.extract(replaced)
          val resolvedBeforeLift                               =
            resolve(replaced, loaded.projectInfo("core").ref)

          assert(
            persistentExtracted.session eq replacedExtracted.session,
            "appendWithoutSession should retain the same SessionSettings identity"
          )
          assertEquals(
            replacedExtracted.structure.settings.map(_.key),
            persistentExtracted.structure.settings.map(_.key)
          )
          assertEquals(
            resolvedBeforeLift,
            new File(replacementDirectory, "version.sbt")
          )

          val error = intercept[IllegalStateException] {
            MonorepoVersionFiles.liftLateBoundVersioningSettings(replaced)
          }
          assert(
            error.getMessage.contains(
              "structure.settings no longer starts with the transformed " +
                "session.mergeSettings prefix"
            ),
            error.getMessage
          )
        }
      }
  }

  test(
    "empty persistent append lifts a same-key appendWithSession override and guards once"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-files-guarded-overlay") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()
        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject("core", coreBase)
        )
      }
      .use { loaded =>
        IO.blocking {
          val persistentDirectory   = new File(loaded.dir, "persistent")
          val replacementDirectory  = new File(loaded.dir, "replacement")
          val persistent            = SbtRuntime.appendSessionSettings(
            loaded.state,
            Seq(
              ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
                (_: ProjectRef, _: State) => new File(persistentDirectory, "version.sbt")
              }
            )
          )
          val transient             = SbtRuntime.appendWithSession(
            persistent,
            Seq(
              ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
                (_: ProjectRef, _: State) => new File(replacementDirectory, "version.sbt")
              }
            )
          )
          val lifted                = MonorepoVersionFiles
            .appendSessionSettingsPreservingVersioning(transient, Seq.empty)
          val liftedAgain           = MonorepoVersionFiles.liftLateBoundVersioningSettings(lifted)
          val rawAppendAfterLift    = Project.extract(lifted).session.rawAppend.length
          val rebuilt               = SbtRuntime.appendSessionSettings(
            lifted,
            Seq(settingKey[String]("force a guarded overlay rebuild") := "rebuilt")
          )
          val rawAppendAfterRebuild = Project.extract(rebuilt).session.rawAppend.length
          val resolved              =
            resolve(rebuilt, loaded.projectInfo("core").ref)

          assert(liftedAgain eq lifted, "trusted lift must return the identical State")
          assertEquals(rawAppendAfterRebuild, rawAppendAfterLift + 1)
          assertEquals(resolved, new File(replacementDirectory, "version.sbt"))
        }
      }
  }

  test("empty persistent append leaves an unguarded no-op state reference-identical") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-files-unguarded-no-op") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()
        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject("core", coreBase)
        )
      }
      .use { loaded =>
        IO.blocking {
          val lifted = MonorepoVersionFiles
            .appendSessionSettingsPreservingVersioning(loaded.state, Seq.empty)
          assert(lifted eq loaded.state)
        }
      }
  }
}
