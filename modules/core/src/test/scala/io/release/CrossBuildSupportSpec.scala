package io.release

import cats.effect.IO
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.sbt.SbtRuntime
import munit.CatsEffectSuite
import sbt.*
import sbt.Keys.*

import java.io.File

class CrossBuildSupportSpec extends CatsEffectSuite {

  private def stateResource(prefix: String) =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        TestSupport.loadedState(
          baseDir = dir,
          projects = Seq(
            Project("root", dir)
              .aggregate(LocalProject("core"))
              .settings(
                scalaVersion     := TestSupport.CurrentScalaVersion,
                name             := "root"
              ),
            Project("core", coreBase).settings(
              scalaVersion       := TestSupport.CurrentScalaVersion,
              crossScalaVersions := Seq(
                TestSupport.CurrentScalaVersion,
                TestSupport.alternateScalaVersion
              ),
              name               := "core"
            )
          ),
          currentProjectId = Some("core")
        )
      }
    }

  private def stateWithoutScalaSettingsResource(prefix: String) =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        TestSupport.loadedState(
          baseDir = dir,
          projects = Seq(
            Project("root", dir)
              .aggregate(LocalProject("core"))
              .settings(name                        := "root"),
            Project("core", coreBase).settings(name := "core")
          ),
          currentProjectId = Some("core")
        )
      }
    }

  private def projectScalaVersionOf(state: State, ref: ProjectRef): IO[Option[String]] =
    IO.blocking {
      val extracted = SbtRuntime.extracted(state)
      (ref / scalaVersion)
        .get(extracted.structure.data)
        .orElse((sbt.GlobalScope / scalaVersion).get(extracted.structure.data))
    }

  private def projectScalaHomeOf(state: State, ref: ProjectRef): IO[Option[File]] =
    IO.blocking {
      val extracted = SbtRuntime.extracted(state)
      (ref / scalaHome)
        .get(extracted.structure.data)
        .flatten
        .orElse((sbt.GlobalScope / scalaHome).get(extracted.structure.data).flatten)
    }

  private def projectTestScalaVersionOf(state: State, ref: ProjectRef): IO[Option[String]] =
    IO.blocking {
      val extracted = SbtRuntime.extracted(state)
      (ref / sbt.Test / scalaVersion).get(extracted.structure.data)
    }

  private def projectTestScalaHomeOf(state: State, ref: ProjectRef): IO[Option[File]] =
    IO.blocking {
      val extracted = SbtRuntime.extracted(state)
      (ref / sbt.Test / scalaHome)
        .get(extracted.structure.data)
        .flatten
    }

  private def projectNameOf(state: State, ref: ProjectRef): IO[String] =
    IO.blocking(SbtRuntime.extracted(state).get(ref / name))

  test(
    "switchScalaVersion plus restoreEntryScalaSession restore project-scoped Scala settings and keep current non-Scala session settings"
  ) {
    val retainedName  = "core-from-current-session"
    val overrideScala = TestSupport.alternateScalaVersion
    val switchedScala = TestSupport.CurrentScalaVersion

    stateResource("cross-build-support-session-restore").use { baseState =>
      val coreRef        = SbtRuntime.extracted(baseState).currentRef
      val entryScalaHome =
        new File(SbtRuntime.extracted(baseState).get(baseDirectory), "scala-home-entry")
      val entryState     = TestSupport.appendSessionSettings(
        baseState,
        Seq(
          coreRef / scalaVersion := overrideScala,
          coreRef / scalaHome    := Some(entryScalaHome)
        )
      )

      for {
        switchedState       <-
          CrossBuildSupport.switchScalaVersion(
            entryState,
            switchedScala,
            ReleaseLogPrefixes.Core
          )
        switchedVersion     <- projectScalaVersionOf(switchedState, coreRef)
        switchedHome        <- projectScalaHomeOf(switchedState, coreRef)
        currentState         = TestSupport.appendSessionSettings(
                                 switchedState,
                                 Seq(coreRef / name := retainedName)
                               )
        currentName         <- projectNameOf(currentState, coreRef)
        currentScalaVersion <- projectScalaVersionOf(currentState, coreRef)
        currentScalaHome    <- projectScalaHomeOf(currentState, coreRef)
        restoredState       <- CrossBuildSupport.restoreEntryScalaSession(
                                 entryState,
                                 currentState
                               )
        restoredVersion     <- projectScalaVersionOf(restoredState, coreRef)
        restoredHome        <- projectScalaHomeOf(restoredState, coreRef)
        restoredName        <- projectNameOf(restoredState, coreRef)
        afterRestoreState    = TestSupport.appendSessionSettings(
                                 restoredState,
                                 Seq(coreRef / name := retainedName)
                               )
        afterRestoreVersion <- projectScalaVersionOf(afterRestoreState, coreRef)
        afterRestoreHome    <- projectScalaHomeOf(afterRestoreState, coreRef)
      } yield {
        assertEquals(
          switchedVersion,
          Some(switchedScala),
          "switchScalaVersion should clear the project-scoped scalaVersion session override"
        )
        assertEquals(
          switchedHome,
          None,
          "switchScalaVersion should clear the project-scoped scalaHome session override"
        )
        assertEquals(currentName, retainedName)
        assertEquals(
          currentScalaVersion,
          Some(switchedScala),
          "appendWithSession after switchScalaVersion must not revert the switched scalaVersion"
        )
        assertEquals(
          currentScalaHome,
          None,
          "appendWithSession after switchScalaVersion must not revert the switched scalaHome"
        )
        assertEquals(restoredVersion, Some(overrideScala))
        assertEquals(restoredHome, Some(entryScalaHome))
        assertEquals(restoredName, retainedName)
        assertEquals(
          afterRestoreVersion,
          Some(overrideScala),
          "appendWithSession after restoreEntryScalaSession must not lose the restored scalaVersion"
        )
        assertEquals(
          afterRestoreHome,
          Some(entryScalaHome),
          "appendWithSession after restoreEntryScalaSession must not lose the restored scalaHome"
        )
      }
    }
  }

  test(
    "restoreEntryScalaSession clears switched Scala settings and keeps current non-Scala session settings when the entry state has no Scala settings"
  ) {
    val retainedName  = "core-from-current-session"
    val switchedScala = TestSupport.CurrentScalaVersion

    stateWithoutScalaSettingsResource("cross-build-support-no-entry-restore").use { baseState =>
      val coreRef = SbtRuntime.extracted(baseState).currentRef

      for {
        switchedState       <-
          CrossBuildSupport.switchScalaVersion(
            baseState,
            switchedScala,
            ReleaseLogPrefixes.Core
          )
        switchedVersion     <- projectScalaVersionOf(switchedState, coreRef)
        currentState         = TestSupport.appendSessionSettings(
                                 switchedState,
                                 Seq(coreRef / name := retainedName)
                               )
        currentScalaVersion <- projectScalaVersionOf(currentState, coreRef)
        restoredState       <- CrossBuildSupport.restoreEntryScalaSession(baseState, currentState)
        restoredVersion     <- projectScalaVersionOf(restoredState, coreRef)
        restoredName        <- projectNameOf(restoredState, coreRef)
      } yield {
        assertEquals(switchedVersion, Some(switchedScala))
        assertEquals(
          currentScalaVersion,
          Some(switchedScala),
          "appendWithSession after switchScalaVersion must not revert the switched scalaVersion"
        )
        assertEquals(restoredVersion, None)
        assertEquals(restoredName, retainedName)
      }
    }
  }

  test(
    "switchScalaVersion plus restoreEntryScalaSession restore config-scoped Scala settings and keep current non-Scala session settings"
  ) {
    val retainedName  = "core-from-current-session"
    val overrideScala = TestSupport.alternateScalaVersion
    val switchedScala = TestSupport.CurrentScalaVersion

    stateResource("cross-build-support-config-session-restore").use { baseState =>
      val coreRef        = SbtRuntime.extracted(baseState).currentRef
      val entryScalaHome =
        new File(SbtRuntime.extracted(baseState).get(baseDirectory), "scala-home-test-entry")
      val entryState     = TestSupport.appendSessionSettings(
        baseState,
        Seq(
          coreRef / sbt.Test / scalaVersion := overrideScala,
          coreRef / sbt.Test / scalaHome    := Some(entryScalaHome)
        )
      )

      for {
        switchedState       <-
          CrossBuildSupport.switchScalaVersion(
            entryState,
            switchedScala,
            ReleaseLogPrefixes.Core
          )
        switchedVersion     <- projectTestScalaVersionOf(switchedState, coreRef)
        switchedHome        <- projectTestScalaHomeOf(switchedState, coreRef)
        currentState         = TestSupport.appendSessionSettings(
                                 switchedState,
                                 Seq(coreRef / name := retainedName)
                               )
        currentName         <- projectNameOf(currentState, coreRef)
        currentScalaVersion <- projectTestScalaVersionOf(currentState, coreRef)
        currentScalaHome    <- projectTestScalaHomeOf(currentState, coreRef)
        restoredState       <- CrossBuildSupport.restoreEntryScalaSession(
                                 entryState,
                                 currentState
                               )
        restoredVersion     <- projectTestScalaVersionOf(restoredState, coreRef)
        restoredHome        <- projectTestScalaHomeOf(restoredState, coreRef)
        restoredName        <- projectNameOf(restoredState, coreRef)
      } yield {
        assertEquals(
          switchedVersion,
          Some(switchedScala),
          "switchScalaVersion should clear the config-scoped scalaVersion session override"
        )
        assertEquals(
          switchedHome,
          None,
          "switchScalaVersion should clear the config-scoped scalaHome session override"
        )
        assertEquals(currentName, retainedName)
        assertEquals(
          currentScalaVersion,
          Some(switchedScala),
          "appendWithSession after switchScalaVersion must not revert the switched scalaVersion " +
            "in a config-scoped lookup"
        )
        assertEquals(
          currentScalaHome,
          None,
          "appendWithSession after switchScalaVersion must not revert the switched scalaHome " +
            "in a config-scoped lookup"
        )
        assertEquals(restoredVersion, Some(overrideScala))
        assertEquals(restoredHome, Some(entryScalaHome))
        assertEquals(restoredName, retainedName)
      }
    }
  }

  test(
    "switchScalaVersion does not mutate session.original — a session clear after switch must restore the build's loaded Scala settings"
  ) {
    val switchedScala = TestSupport.alternateScalaVersion
    stateResource("cross-build-support-session-clear-preserves-original").use { baseState =>
      val coreRef   = SbtRuntime.extracted(baseState).currentRef
      val baseScala = TestSupport.CurrentScalaVersion
      for {
        switchedState   <- CrossBuildSupport.switchScalaVersion(
                             baseState,
                             switchedScala,
                             ReleaseLogPrefixes.Core
                           )
        switchedVersion <- projectScalaVersionOf(switchedState, coreRef)
        clearedState     = simulateSessionClear(switchedState)
        clearedVersion  <- projectScalaVersionOf(clearedState, coreRef)
      } yield {
        assertEquals(
          switchedVersion,
          Some(switchedScala),
          "switchScalaVersion should make the new version visible"
        )
        assertEquals(
          clearedVersion,
          Some(baseScala),
          "after a `session clear` (rawAppend dropped) the build's loaded scalaVersion " +
            "must be visible again — switchScalaVersion must not have mutated session.original"
        )
      }
    }
  }

  /** Simulate sbt's `session clear`: drop session.rawAppend (and append) but keep
    * session.original, then rebuild the structure from the cleared session.
    */
  private def simulateSessionClear(state: State): State = {
    val extracted                                             = SbtRuntime.extracted(state)
    import extracted.*
    implicit val showKey: sbt.util.Show[sbt.Def.ScopedKey[?]] = extracted.showKey
    val cleared                                               = session.copy(rawAppend = Nil, append = Map.empty)
    val newStructure                                          = LoadCompat.reapply(cleared.mergeSettings, structure)
    Project.setProject(cleared, newStructure, state)
  }
}
