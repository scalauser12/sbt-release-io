package io.release

import cats.effect.IO
import io.release.internal.ReleaseLogPrefixes
import sbt.Def.ScopedKey
import sbt.util.Show
import sbt.{internal as _, *}

/** Shared cross-build utilities used by both the core [[ReleaseComposer]] and the
  * monorepo `MonorepoComposer`. Switching Scala versions requires a full project-structure
  * reload to invalidate incremental compilation caches.
  */
private[release] object CrossBuildSupport {

  /** Switch Scala version by fully reloading the project structure.
    * Based on `sbt.Cross.switchVersion` logic.
    * Wraps the entire operation in `IO.blocking` since it calls sbt internals
    * (`LoadCompat.reapply`, `Project.setProject`) that perform blocking I/O.
    */
  def switchScalaVersion(state: State, version: String): IO[State] =
    IO.blocking {
      val extracted                            = Project.extract(state)
      import extracted.*
      implicit val showKey: Show[ScopedKey[?]] = extracted.showKey

      state.log.info(s"${ReleaseLogPrefixes.Core} Setting scala version to $version")

      val add = Seq(
        GlobalScope / Keys.scalaVersion := version,
        GlobalScope / Keys.scalaHome    := None
      )

      val cleared      = session.mergeSettings.filterNot(isScalaSessionSetting)
      val newStructure = LoadCompat.reapply(add ++ cleared, structure)
      Project.setProject(session, newStructure, state)
    }

  /** Resolve the entry Scala version for a project-like scope, falling back to global scope. */
  def resolveEntryScalaVersion(extracted: Extracted, ref: ProjectRef): Option[String] =
    (ref / Keys.scalaVersion)
      .get(extracted.structure.data)
      .orElse((GlobalScope / Keys.scalaVersion).get(extracted.structure.data))

  /** Restore only the Scala-related session settings from the captured entry state.
    * Keeps the current session's non-Scala settings intact while reapplying the entry
    * `scalaVersion` / `scalaHome` slice.
    */
  def restoreEntryScalaSession(entryState: State, currentState: State): IO[State] =
    IO.blocking {
      val currentExtracted                     = Project.extract(currentState)
      val entryExtracted                       = Project.extract(entryState)
      import currentExtracted.*
      implicit val showKey: Show[ScopedKey[?]] = currentExtracted.showKey

      val currentSettingsWithoutScala = session.mergeSettings.filterNot(isScalaSessionSetting)
      val entryScalaSettings          =
        entryExtracted.session.mergeSettings.filter(isScalaSessionSetting)
      val newStructure                =
        LoadCompat.reapply(entryScalaSettings ++ currentSettingsWithoutScala, structure)
      Project.setProject(session, newStructure, currentState)
    }

  /** Check if a setting is Scala-session state (`scalaVersion`, `scalaHome`). */
  private def isScalaSessionSetting(s: Setting[?]): Boolean =
    s.key match {
      case ScopedKey(Scope(_, Zero, Zero, _), key)
          if key == Keys.scalaVersion.key || key == Keys.scalaHome.key =>
        true
      case _ => false
    }
}
