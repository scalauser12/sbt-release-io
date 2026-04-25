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

  /** Two-project state (root + core + api) for tests that need to verify per-project
    * cross-build scoping semantics. Each project has its own `scalaVersion`.
    */
  private def twoProjectStateResource(prefix: String) =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val coreBase = new File(dir, "core")
        val apiBase  = new File(dir, "api")
        coreBase.mkdirs()
        apiBase.mkdirs()

        TestSupport.loadedState(
          baseDir = dir,
          projects = Seq(
            Project("root", dir)
              .aggregate(LocalProject("core"), LocalProject("api"))
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
            ),
            Project("api", apiBase).settings(
              scalaVersion       := TestSupport.CurrentScalaVersion,
              crossScalaVersions := Seq(TestSupport.CurrentScalaVersion),
              name               := "api"
            )
          ),
          currentProjectId = Some("core")
        )
      }
    }

  /** Build state with `core / scalaVersion` and `core / scalaHome` set as build-loaded
    * project settings (i.e. living in `session.original`). Used by tests that simulate
    * a user-set entry override that should survive a switch/restore cycle.
    */
  private def stateResourceWithProjectScalaOverride(
      prefix: String,
      projectScala: String,
      projectScalaHomeName: String
  ) =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val coreBase      = new File(dir, "core")
        coreBase.mkdirs()
        val scalaHomeFile = new File(coreBase, projectScalaHomeName)

        val state = TestSupport.loadedState(
          baseDir = dir,
          projects = Seq(
            Project("root", dir)
              .aggregate(LocalProject("core"))
              .settings(
                scalaVersion     := TestSupport.CurrentScalaVersion,
                name             := "root"
              ),
            Project("core", coreBase).settings(
              scalaVersion       := projectScala,
              scalaHome          := Some(scalaHomeFile),
              crossScalaVersions := Seq(
                TestSupport.CurrentScalaVersion,
                TestSupport.alternateScalaVersion
              ),
              name               := "core"
            )
          ),
          currentProjectId = Some("core")
        )
        (state, scalaHomeFile)
      }
    }

  /** Build state with `core / Test / scalaVersion` and `core / Test / scalaHome` set as
    * build-loaded project settings. Used by the Test-scoped override scenario.
    */
  private def stateResourceWithTestScalaOverride(
      prefix: String,
      testScala: String,
      testScalaHomeName: String
  ) =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val coreBase      = new File(dir, "core")
        coreBase.mkdirs()
        val scalaHomeFile = new File(coreBase, testScalaHomeName)

        val state = TestSupport.loadedState(
          baseDir = dir,
          projects = Seq(
            Project("root", dir)
              .aggregate(LocalProject("core"))
              .settings(
                scalaVersion          := TestSupport.CurrentScalaVersion,
                name                  := "root"
              ),
            Project("core", coreBase).settings(
              scalaVersion            := TestSupport.CurrentScalaVersion,
              sbt.Test / scalaVersion := testScala,
              sbt.Test / scalaHome    := Some(scalaHomeFile),
              crossScalaVersions      := Seq(
                TestSupport.CurrentScalaVersion,
                TestSupport.alternateScalaVersion
              ),
              name                    := "core"
            )
          ),
          currentProjectId = Some("core")
        )
        (state, scalaHomeFile)
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

    stateResourceWithProjectScalaOverride(
      prefix = "cross-build-support-session-restore",
      projectScala = overrideScala,
      projectScalaHomeName = "scala-home-entry"
    ).use { case (entryState, entryScalaHome) =>
      val coreRef = SbtRuntime.extracted(entryState).currentRef

      for {
        switchedState       <-
          CrossBuildSupport.switchScalaVersion(
            entryState,
            switchedScala,
            Seq(coreRef),
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
            Seq(coreRef),
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
    "switchScalaVersion preserves config-scoped Scala settings (matches sbt's Cross.switchVersion semantics)"
  ) {
    val retainedName  = "core-from-current-session"
    val overrideScala = TestSupport.alternateScalaVersion
    val switchedScala = TestSupport.CurrentScalaVersion

    stateResourceWithTestScalaOverride(
      prefix = "cross-build-support-config-session-restore",
      testScala = overrideScala,
      testScalaHomeName = "scala-home-test-entry"
    ).use { case (entryState, entryScalaHome) =>
      val coreRef = SbtRuntime.extracted(entryState).currentRef

      for {
        switchedState       <-
          CrossBuildSupport.switchScalaVersion(
            entryState,
            switchedScala,
            Seq(coreRef),
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
          Some(overrideScala),
          "config-scoped scalaVersion overrides are preserved across the switch — sbt's " +
            "stock Cross only touches per-project (project axis) scope"
        )
        assertEquals(
          switchedHome,
          Some(entryScalaHome),
          "config-scoped scalaHome overrides are preserved across the switch"
        )
        assertEquals(currentName, retainedName)
        assertEquals(
          currentScalaVersion,
          Some(overrideScala),
          "config-scoped scalaVersion remains preserved through subsequent appendWithSession"
        )
        assertEquals(
          currentScalaHome,
          Some(entryScalaHome),
          "config-scoped scalaHome remains preserved through subsequent appendWithSession"
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
                             Seq(coreRef),
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

  test(
    "restoreEntryScalaSession preserves the entry session's rawAppend Scala slice (e.g. an interactive `++ X` switch made before the release)"
  ) {
    val baseScala     = TestSupport.CurrentScalaVersion
    val overrideScala = TestSupport.alternateScalaVersion
    val switchedScala = TestSupport.CurrentScalaVersion

    stateResource("cross-build-support-rawappend-restore").use { baseState =>
      val coreRef    = SbtRuntime.extracted(baseState).currentRef
      val crossScope = sbt.Scope(sbt.Select(coreRef), sbt.Zero, sbt.Zero, sbt.Zero)
      // Simulate `++ overrideScala` in the interactive sbt session before the release:
      // sbt's Cross.setScalaVersionsForProjects writes a per-project setting into
      // session.rawAppend at scope `Scope(Select(ref), Zero, Zero, Zero)`.
      val entryState = simulateInteractiveCrossSwitch(
        baseState,
        Seq(crossScope / scalaVersion := overrideScala)
      )

      for {
        entryVersion    <- projectScalaVersionOf(entryState, coreRef)
        switchedState   <- CrossBuildSupport.switchScalaVersion(
                             entryState,
                             switchedScala,
                             Seq(coreRef),
                             ReleaseLogPrefixes.Core
                           )
        switchedVersion <- projectScalaVersionOf(switchedState, coreRef)
        restoredState   <- CrossBuildSupport.restoreEntryScalaSession(entryState, switchedState)
        restoredVersion <- projectScalaVersionOf(restoredState, coreRef)
        clearedState     = simulateSessionClear(restoredState)
        clearedVersion  <- projectScalaVersionOf(clearedState, coreRef)
      } yield {
        assertEquals(
          entryVersion,
          Some(overrideScala),
          "entry session's `++` switch must be active going into the release"
        )
        assertEquals(switchedVersion, Some(switchedScala))
        assertEquals(
          restoredVersion,
          Some(overrideScala),
          "after restoreEntryScalaSession the user's pre-release `++` switch must still be " +
            "active — restoring to the build-loaded version would silently drop the user's switch"
        )
        assertEquals(
          clearedVersion,
          Some(baseScala),
          "the user's `++` switch is recorded in rawAppend (the slice `session clear` drops), " +
            "so a post-release `session clear` should still cleanly return to the build-loaded version"
        )
      }
    }
  }

  test(
    "switchScalaVersion preserves rawAppend Scala overrides for projects outside affectedRefs (e.g. user's pre-release `++ X` on a sibling project)"
  ) {
    val baseScala     = TestSupport.CurrentScalaVersion
    val plusPlusScala = TestSupport.alternateScalaVersion
    val switchedScala = TestSupport.CurrentScalaVersion

    twoProjectStateResource("cross-build-support-rawappend-sibling-preserved").use { baseState =>
      val coreRef    =
        SbtRuntime.extracted(baseState).structure.allProjectRefs.find(_.project == "core").get
      val apiRef     =
        SbtRuntime.extracted(baseState).structure.allProjectRefs.find(_.project == "api").get
      val coreScope  = sbt.Scope(sbt.Select(coreRef), sbt.Zero, sbt.Zero, sbt.Zero)
      val apiScope   = sbt.Scope(sbt.Select(apiRef), sbt.Zero, sbt.Zero, sbt.Zero)
      // Simulate `++ plusPlusScala` in interactive sbt: sbt's
      // Cross.setScalaVersionsForProjects writes a per-project Scala setting into
      // session.rawAppend at scope `Scope(Select(ref), Zero, Zero, Zero)` for every
      // project with a matching crossScalaVersions.
      val entryState = simulateInteractiveCrossSwitch(
        baseState,
        Seq(
          coreScope / scalaVersion := plusPlusScala,
          apiScope / scalaVersion  := plusPlusScala
        )
      )

      for {
        entryCore     <- projectScalaVersionOf(entryState, coreRef)
        entryApi      <- projectScalaVersionOf(entryState, apiRef)
        switchedState <- CrossBuildSupport.switchScalaVersion(
                           entryState,
                           switchedScala,
                           Seq(coreRef),
                           ReleaseLogPrefixes.Core
                         )
        switchedCore  <- projectScalaVersionOf(switchedState, coreRef)
        switchedApi   <- projectScalaVersionOf(switchedState, apiRef)
        restoredState <- CrossBuildSupport.restoreEntryScalaSession(entryState, switchedState)
        restoredCore  <- projectScalaVersionOf(restoredState, coreRef)
        restoredApi   <- projectScalaVersionOf(restoredState, apiRef)
      } yield {
        assertEquals(entryCore, Some(plusPlusScala), "entry: core should be at user's `++` value")
        assertEquals(entryApi, Some(plusPlusScala), "entry: api should be at user's `++` value")
        assertEquals(
          switchedCore,
          Some(switchedScala),
          "switch: core (in affectedRefs) is at the new switch version"
        )
        assertEquals(
          switchedApi,
          Some(plusPlusScala),
          "switch: api (NOT in affectedRefs) keeps its user `++` rawAppend value — the strip " +
            "must be scoped to affectedRefs, not blanket across all project axes; otherwise " +
            "any hook or transitive task touching api during core's iteration would see api " +
            "back at its build-loaded value"
        )
        assertEquals(restoredCore, Some(plusPlusScala), "restore: core back to entry view")
        assertEquals(restoredApi, Some(plusPlusScala), "restore: api back to entry view")
      }
    }
  }

  test(
    "switchScalaVersion strips affected-ref `scalaInstance` from session.rawAppend (e.g. an interactive `++ --scala-home X` pin) but preserves `scalaInstance` in session.original (build.sbt pins) — matches sbt's stock Cross"
  ) {
    val switchedScala = TestSupport.alternateScalaVersion

    stateResource("cross-build-support-scala-instance-rawappend").use { baseState =>
      val coreRef   = SbtRuntime.extracted(baseState).currentRef
      val coreScope = sbt.Scope(sbt.Select(coreRef), sbt.Zero, sbt.Zero, sbt.Zero)

      // Simulate `++ --scala-home X`: sbt's Cross writes `core / scalaInstance := <pinned>`
      // to session.rawAppend. We never invoke the task — its presence in
      // `structure.settings` at `core / scalaInstance` scope as a Setting from rawAppend
      // is what we're checking.
      val rawAppendPin: Setting[?] = (coreScope / scalaInstance) := {
        throw new IllegalStateException("rawAppend scalaInstance pin should be stripped on switch")
      }
      val entryWithRawAppendPin    = simulateInteractiveCrossSwitch(baseState, Seq(rawAppendPin))

      def rawAppendPinPresent(state: State): IO[Boolean] = IO.blocking {
        SbtRuntime
          .extracted(state)
          .session
          .rawAppend
          .exists(s => s.key.scope == coreScope && s.key.key == scalaInstance.key)
      }

      for {
        entryHasPin    <- rawAppendPinPresent(entryWithRawAppendPin)
        switchedState  <- CrossBuildSupport.switchScalaVersion(
                            entryWithRawAppendPin,
                            switchedScala,
                            Seq(coreRef),
                            ReleaseLogPrefixes.Core
                          )
        switchedHasPin <- rawAppendPinPresent(switchedState)
        restoredState  <- CrossBuildSupport.restoreEntryScalaSession(
                            entryWithRawAppendPin,
                            switchedState
                          )
        restoredHasPin <- rawAppendPinPresent(restoredState)
      } yield {
        assert(entryHasPin, "entry: rawAppend should carry the simulated `++ --scala-home` pin")
        assert(
          !switchedHasPin,
          "switch: per-affected-ref rawAppend strip must drop the `core / scalaInstance` pin " +
            "from rawAppend so the standard scalaInstance task derivation in `session.original` " +
            "(via `Defaults.coreDefaultSettings`) takes over and recomputes against the switched " +
            "scalaVersion. (Pins in `session.original` — i.e. real `build.sbt scalaInstance := X` " +
            "— are intentionally preserved, matching sbt's stock `Cross.setScalaVersionsForProjects`.)"
        )
        assert(
          restoredHasPin,
          "restore: entry rawAppend pin is reinstated so the user's interactive `++` state is " +
            "left exactly where it started"
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

  /** Simulate sbt's `++ X` command: write the provided Scala settings into
    * `session.rawAppend` (where sbt's `Cross.setScalaVersionsForProjects` puts them) and
    * rebuild the structure. Settings should already carry concrete (non-`This`) scopes,
    * matching what sbt's Cross emits.
    */
  private def simulateInteractiveCrossSwitch(state: State, settings: Seq[Setting[?]]): State = {
    val extracted                                             = SbtRuntime.extracted(state)
    import extracted.*
    implicit val showKey: sbt.util.Show[sbt.Def.ScopedKey[?]] = extracted.showKey
    val newSession                                            = session.appendRaw(settings)
    val newStructure                                          = LoadCompat.reapply(newSession.mergeSettings, structure)
    Project.setProject(newSession, newStructure, state)
  }
}
