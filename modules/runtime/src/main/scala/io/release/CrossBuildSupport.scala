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

  /** Switch Scala version by reapplying the project structure with new Scala settings
    * and recording them in `session.rawAppend`. Modeled on
    * `_root_.sbt.Cross.switchVersion`: per-project scoped overrides
    * (`projectRef / scalaVersion := newVersion`) are added to `rawAppend` for every
    * project in the build, plus a `Global` pair, so a same-scope `scalaVersion :=` from
    * `build.sbt` (which lives in `session.original`) is overridden via mergeSettings
    * ordering rather than by mutating the build slice.
    *
    * Only `session.rawAppend` is mutated. `session.original` (the loaded build
    * definition) and `session.append` (user `set` commands and similar) are preserved
    * intact, so an interactive `session clear` after a release returns the build to a
    * coherent state without forcing a `reload`. The session update also ensures any
    * subsequent `Extracted.appendWithSession` rebuild — which reads `session.mergeSettings`
    * — preserves the switch instead of silently undoing it (this was the publish-path
    * bug that motivated the original fix).
    *
    * Config-scoped overrides (`projectRef / Test / scalaVersion := X`) are temporarily
    * cleared at the structure level for the duration of the cross-build so the switched
    * version takes effect during the iteration; they are restored from
    * `entryState.structure.settings` by [[restoreEntryScalaSession]].
    *
    * Wraps the entire operation in `IO.blocking` since it calls sbt internals
    * (`LoadCompat.reapply`, `Project.setProject`) that perform blocking I/O.
    */
  def switchScalaVersion(state: State, version: String, logPrefix: String): IO[State] =
    IO.blocking {
      state.log.info(s"$logPrefix Setting scala version to $version")
      val extracted   = Project.extract(state)
      // Use explicit Zero axes (matching sbt's `Cross.setScalaVersionsForProjects`) so the
      // resulting `Setting.key.scope` has `Scope(Select(ref), Zero, Zero, Zero)` rather than
      // `Scope(Select(ref), This, This, This)`. Without explicit Zero axes the stored scope
      // still carries `This`, which would defeat `isScalaSetting` and cause `rawAppend` to
      // grow unbounded across cross-build iterations.
      val perProject  = extracted.structure.allProjectRefs.flatMap { ref =>
        val scope = Scope(Select(ref), Zero, Zero, Zero)
        Seq(
          scope / Keys.scalaVersion := version,
          scope / Keys.scalaHome    := None
        )
      }
      val newSettings = Seq(
        GlobalScope / Keys.scalaVersion := version,
        GlobalScope / Keys.scalaHome    := None
      ) ++ perProject
      reapplyWithScalaSettings(state, newSettings)
    }

  /** Restore the captured entry Scala slice. Entry settings are read from
    * `entryState.structure.settings` (which covers build.sbt-loaded scala, settings
    * added via `set` or `appendWithSession`, and any other source that ends up in the
    * structure) and re-applied via `session.rawAppend`. Because [[switchScalaVersion]]
    * never mutates `session.original` or `session.append`, restoring is purely additive
    * on the rawAppend slice — the build's original definition is still intact in
    * `session.original` and is implicitly re-asserted by stripping the switch's
    * rawAppend additions.
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

    // Mutate ONLY `session.rawAppend`. `session.original` (the loaded build slice) and
    // `session.append` (user `set` commands) are preserved so the switch is fully
    // transient: an interactive `session clear` drops the rawAppend additions and the
    // build's loaded Scala settings take effect again without needing a `reload`.
    //
    // The structure rebuild reads from `structure.settings`, not `session.mergeSettings`,
    // because `Extracted.appendWithSession` rebuilds the structure with appended settings
    // but never persists them into the session. Rebuilding from the structure preserves
    // those prior `appendWithSession` additions; rebuilding from the session would drop
    // them.
    val filteredRawAppend = session.rawAppend.filterNot(isScalaSetting)
    val newSession        = session.copy(rawAppend = filteredRawAppend ++ scalaSettings)
    val cleared           = structure.settings.filterNot(isScalaSetting)
    val newStructure      = LoadCompat.reapply(scalaSettings ++ cleared, structure)
    Project.setProject(newSession, newStructure, currentState)
  }

  /** Matches a `scalaVersion` or `scalaHome` setting at any scope (Global, ThisBuild,
    * project, config). The structure rebuild filters all of them so the switched
    * version is the only Scala value the structure resolves; per-project and Global
    * scopes are also recorded in `session.rawAppend` so subsequent `appendWithSession`
    * calls (which rebuild from `mergeSettings`) preserve the switch.
    */
  private def isScalaSetting(s: Setting[?]): Boolean =
    s.key match {
      case ScopedKey(Scope(_, _, Zero, _), key)
          if key == Keys.scalaVersion.key || key == Keys.scalaHome.key =>
        true
      case _ => false
    }
}
