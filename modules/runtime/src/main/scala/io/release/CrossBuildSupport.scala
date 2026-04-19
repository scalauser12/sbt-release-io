package io.release

import cats.effect.IO
import _root_.sbt.Def.ScopedKey
import _root_.sbt.util.Show
import _root_.sbt.{internal as _, *}

/** Shared cross-build utilities used by both the core [[ReleaseComposer]] and the
  * monorepo `MonorepoComposer`. Switching Scala versions requires a full project-structure
  * reload to invalidate incremental compilation caches.
  */
private[release] object CrossBuildSupport {

  /** Switch Scala version by fully reloading the project structure.
    * Based on `_root_.sbt.Cross.switchVersion` logic.
    * Wraps the entire operation in `IO.blocking` since it calls sbt internals
    * (`LoadCompat.reapply`, `Project.setProject`) that perform blocking I/O.
    *
    * Only global-scope `scalaVersion` / `scalaHome` settings are replaced. Project-scope
    * overrides (e.g. `projectRef / scalaVersion := "2.13.x"`) are preserved intact and
    * will continue to shadow the new global within those projects, matching
    * `_root_.sbt.Cross.switchVersion` semantics.
    */
  def switchScalaVersion(state: State, version: String, logPrefix: String): IO[State] =
    IO.blocking {
      state.log.info(s"$logPrefix Setting scala version to $version")
      reapplyWithScalaSettings(
        state,
        Seq(
          GlobalScope / Keys.scalaVersion := version,
          GlobalScope / Keys.scalaHome    := None
        )
      )
    }

  /** Restore only the Scala-related session settings from the captured entry state.
    * Keeps the current session's non-Scala settings intact while reapplying the entry
    * `scalaVersion` / `scalaHome` slice.
    *
    * @param entryState   session captured before the cross-build modified Scala settings;
    *                     source of the Scala slice to restore.
    * @param currentState session in its post-cross-build form; target on which the
    *                     structure is reapplied.
    */
  def restoreEntryScalaSession(entryState: State, currentState: State): IO[State] =
    IO.blocking {
      val entryScalaSettings =
        Project.extract(entryState).structure.settings.filter(isScalaSetting)
      reapplyWithScalaSettings(currentState, entryScalaSettings)
    }

  private def reapplyWithScalaSettings(
      currentState: State,
      scalaSettings: Seq[Setting[?]]
  ): State = {
    val extracted                            = Project.extract(currentState)
    import extracted.*
    // Re-declare as implicit: Scala 3's import semantics don't propagate the implicit
    // status of imported members uniformly with Scala 2's, so re-binding keeps
    // LoadCompat.reapply's implicit Show[ScopedKey[?]] resolvable on both lanes.
    implicit val showKey: Show[ScopedKey[?]] = extracted.showKey

    val cleared      = structure.settings.filterNot(isScalaSetting)
    val newStructure = LoadCompat.reapply(scalaSettings ++ cleared, structure)
    Project.setProject(session, newStructure, currentState)
  }

  /** Matches only global-scope (`Zero` task axis) `scalaVersion` / `scalaHome` settings —
    * per-project overrides are deliberately excluded so they survive a version switch.
    */
  private def isScalaSetting(s: Setting[?]): Boolean =
    s.key match {
      case ScopedKey(Scope(_, _, Zero, _), key)
          if key == Keys.scalaVersion.key || key == Keys.scalaHome.key =>
        true
      case _ => false
    }
}
