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

  /** Captured state needed to drive a per-project cross-build iteration: the project's
    * distinct `crossScalaVersions`, the entry sbt `State` to restore against on
    * completion, and the per-iteration "compatible refs" filter. Bundled because the
    * tracked and untracked monorepo cross-build paths capture the same three values.
    */
  final case class ProjectCrossBuildSetup(
      crossVersions: Seq[String],
      entryState: State,
      affectedFor: String => Seq[ProjectRef]
  )

  /** Capture the per-project cross-build setup. Reads `crossScalaVersions` for `projectRef`
    * (deduped), captures the per-iteration compatibility filter once via
    * [[affectedRefsByVersion]], and fails fast with a contextual message when no versions
    * are configured — matching the inline validation that previously appeared in both the
    * tracked and untracked monorepo cross-build paths.
    */
  def loadProjectSetup(
      state: State,
      projectRef: ProjectRef,
      projectName: String,
      logPrefix: String
  ): IO[ProjectCrossBuildSetup] =
    IO.blocking {
      val extracted     = Project.extract(state)
      val crossVersions =
        extracted
          .getOpt(projectRef / Keys.crossScalaVersions)
          .getOrElse(Seq.empty)
          .distinct
      val affectedFor   = affectedRefsByVersion(state)
      (crossVersions, affectedFor)
    }.flatMap { case (crossVersions, affectedFor) =>
      if (crossVersions.isEmpty)
        IO.raiseError(
          new IllegalStateException(
            s"$logPrefix Cross-build enabled but $projectName has empty crossScalaVersions"
          )
        )
      else IO.pure(ProjectCrossBuildSetup(crossVersions, state, affectedFor))
    }

  /** Switch Scala version by reapplying the project structure with per-project Scala
    * overrides recorded in `session.rawAppend`. Modeled on
    * `_root_.sbt.Cross.setScalaVersionsForProjects`: scoped overrides
    * (`projectRef / scalaVersion := newVersion`) are added to `rawAppend` for the
    * `affectedRefs` only — projects outside that selection keep their build-loaded
    * `scalaVersion`. Same-scope `scalaVersion :=` settings from `build.sbt` (which live
    * in `session.original`) are overridden via mergeSettings ordering rather than by
    * mutating the build slice.
    *
    * No `Global` setting is added: sbt's stock `Cross.switchVersion` doesn't add one
    * either, and adding one here would force any project without an explicit
    * `scalaVersion` (i.e. one that delegates to the Global default) onto the cross-build
    * version regardless of selection. Callers that want all projects switched (e.g. core
    * single-project releases) pass `extracted.structure.allProjectRefs` explicitly.
    *
    * Only `session.rawAppend` is mutated. `session.original` (the loaded build
    * definition) and `session.append` (user `set` commands and similar) are preserved
    * intact, so an interactive `session clear` after a release returns the build to a
    * coherent state without forcing a `reload`. The session update also ensures any
    * subsequent `Extracted.appendWithSession` rebuild — which reads `session.mergeSettings`
    * — preserves the switch instead of silently undoing it (the publish-path bug that
    * motivated the original fix).
    *
    * Config-scoped overrides (`projectRef / Test / scalaVersion := X`) are preserved
    * across the switch — sbt's stock `Cross.switchVersion` doesn't touch them either,
    * and overriding an explicit Test-scope value would silently change the compiler
    * used for tests during a cross-build iteration.
    *
    * Wraps the entire operation in `IO.blocking` since it calls sbt internals
    * (`LoadCompat.reapply`, `Project.setProject`) that perform blocking I/O.
    *
    * @param affectedRefs project refs to switch. Empty input is allowed: the rebuild
    *                     still strips any pre-existing Scala settings from
    *                     `session.rawAppend` and re-installs the entry Scala slice from
    *                     `session.original` / `session.append`, but adds no new
    *                     overrides. (Functionally equivalent to passing
    *                     `Seq.empty` to [[restoreEntryScalaSession]] when entry has no
    *                     `++` rawAppend slice.)
    */
  def switchScalaVersion(
      state: State,
      version: String,
      affectedRefs: Seq[ProjectRef],
      logPrefix: String
  ): IO[State] =
    IO.blocking {
      state.log.info(s"$logPrefix Setting scala version to $version")
      // Use explicit Zero axes (matching sbt's `Cross.setScalaVersionsForProjects`) so the
      // resulting `Setting.key.scope` has `Scope(Select(ref), Zero, Zero, Zero)` rather than
      // `Scope(Select(ref), This, This, This)`. Without explicit Zero axes the stored scope
      // still carries `This`, which would defeat `isScalaSetting` and cause `rawAppend` to
      // grow unbounded across cross-build iterations.
      val affected    = affectedRefs.toSet
      val newSettings = affectedRefs.flatMap { ref =>
        val scope = Scope(Select(ref), Zero, Zero, Zero)
        Seq(
          scope / Keys.scalaVersion := version,
          scope / Keys.scalaHome    := None
        )
      }
      rebuildWithScalaSlice(
        currentState = state,
        rawAppendStrip = isAffectedScalaSetting(_, affected),
        addToRawAppend = newSettings
      )
    }

  /** Strip the switch's `rawAppend` additions and reassert the entry view.
    *
    * Because [[switchScalaVersion]] never mutates `session.original` or `session.append`,
    * those slices still carry the build's loaded Scala settings and any user `set`
    * overrides; the structure is rebuilt from `session.mergeSettings`'s Scala slice
    * (post-strip) so those entry-view settings flow back into `structure.data`.
    *
    * The entry state's `session.rawAppend` Scala slice — which is where sbt's `++ X`
    * command persists its own switches — is captured and re-installed on top, so an
    * interactive session that was already on `++ X` before the release is left on `++
    * X` after, rather than silently snapping back to the build's loaded version.
    *
    * Settings applied via `Extracted.appendWithSession` (which never persist into the
    * session) are intentionally NOT preserved by any [[rebuildWithScalaSlice]] call,
    * including the switch itself — the rebuild reads the Scala slice from
    * `session.mergeSettings`, which doesn't see appendWithSession's structure-only
    * additions. Replaying them would inject duplicates into `rawAppend` that survive a
    * later `session clear`, "sticking" what was meant to be a transient override.
    */
  def restoreEntryScalaSession(entryState: State, currentState: State): IO[State] =
    IO.blocking {
      val entryRawAppendScala =
        Project.extract(entryState).session.rawAppend.filter(isScalaSetting)
      // Restore strips ALL Scala from rawAppend and replays the entry slice. This is
      // intentionally broader than the per-iteration switch's narrow strip: we want
      // to drop every switch addition we accumulated across the cross-build (regardless
      // of which iterations they came from) and reset to whatever the user had at
      // entry.
      rebuildWithScalaSlice(
        currentState = currentState,
        rawAppendStrip = isScalaSetting,
        addToRawAppend = entryRawAppendScala
      )
    }

  /** Rebuild the project structure with a fresh Scala slice. The Scala slice is taken
    * from the post-strip `session.mergeSettings` (so build-loaded `session.original`
    * and user-set `session.append` flow through), with the caller's `addToRawAppend`
    * folded into `session.rawAppend`. This same path serves both switch (caller passes
    * the new per-project overrides plus a narrow per-affected-ref strip predicate) and
    * restore (caller passes the entry rawAppend slice plus a broad strip predicate).
    *
    * `rawAppendStrip` controls which existing rawAppend Scala settings are dropped
    * before the new ones are appended. Switch passes a narrow per-affected-ref filter
    * so it doesn't accidentally drop user-set `++` overrides on sibling projects;
    * restore passes a broad filter so every accumulated switch addition is dropped
    * before the entry slice is replayed.
    *
    * Using `mergeSettings` rather than the structure's own settings list makes the
    * post-switch view consistent before and after any later
    * `Extracted.appendWithSession` rebuild — `appendWithSession` itself rebuilds from
    * `session.mergeSettings`, so anything that survives our rebuild here will also
    * survive theirs. Non-Scala settings are preserved from the current structure
    * (including settings applied via prior `appendWithSession`).
    *
    * Config-scoped overrides like `core / Test / scalaVersion := X` from `build.sbt`
    * (which live in `session.original`) are intentionally preserved across the switch:
    * sbt's stock `Cross.switchVersion` only touches per-project (project axis) scope,
    * and overriding a user's explicit Test-scope value would silently change the
    * compiler used for tests during a cross-build iteration.
    *
    * Explicit `scalaInstance` pins are also preserved (matching sbt's stock `Cross`):
    * sbt's `Defaults.coreDefaultSettings` injects `scalaInstance := scalaInstanceTask.value`
    * at per-project scope as the standard derivation, so removing per-project
    * `scalaInstance` would also remove the standard task and break compile. The narrow
    * `rawAppendStrip` still drops `scalaInstance` pins from rawAppend (the slice
    * `++ --scala-home X` writes to), but `original` / `append` pins survive — exactly
    * as they do under `_root_.sbt.Cross`.
    */
  private def rebuildWithScalaSlice(
      currentState: State,
      rawAppendStrip: Setting[?] => Boolean,
      addToRawAppend: Seq[Setting[?]]
  ): State = {
    val extracted                            = Project.extract(currentState)
    import extracted.*
    // Re-declare as implicit: Scala 3's import semantics don't propagate the implicit
    // status of imported members uniformly with Scala 2's, so re-binding keeps
    // LoadCompat.reapply's implicit Show[ScopedKey[?]] resolvable on both lanes. Do not
    // remove without verifying both `sbt -Dsbt.version=1.x` and `./bin/sbt2-clean`.
    implicit val showKey: Show[ScopedKey[?]] = extracted.showKey

    val filteredRawAppend = session.rawAppend.filterNot(rawAppendStrip)
    val newSession        = session.copy(rawAppend = filteredRawAppend ++ addToRawAppend)
    val cleared           = structure.settings.filterNot(isScalaSetting)
    val sessionScala      = newSession.mergeSettings.filter(isScalaSetting)
    val newStructure      = LoadCompat.reapply(cleared ++ sessionScala, structure)
    Project.setProject(newSession, newStructure, currentState)
  }

  /** Build a function that, given an iteration scalaVersion, returns the project refs
    * whose `crossScalaVersions` contains it — the sbt-stock `Cross.switchVersion`
    * compatibility filter. Captures `extracted.structure.allProjectRefs` and each ref's
    * `crossScalaVersions` once so the per-iteration call is a cheap map lookup.
    *
    * The returned `Seq` is the natural `affectedRefs` argument to [[switchScalaVersion]]:
    * passing every compatible project per iteration aligns inter-project deps so a
    * cross-built monorepo's compile/test/publish traverses the dep graph at a coherent
    * Scala version. Projects without matching `crossScalaVersions` are deliberately
    * left at their entry version — sbt's documented "skip incompatible" behavior.
    */
  def affectedRefsByVersion(state: State): String => Seq[ProjectRef] = {
    val extracted                              = Project.extract(state)
    val supports: Map[ProjectRef, Set[String]] = extracted.structure.allProjectRefs.iterator.map {
      ref =>
        ref -> extracted.getOpt(ref / Keys.crossScalaVersions).toSeq.flatten.toSet
    }.toMap
    val refs: Seq[ProjectRef]                  = extracted.structure.allProjectRefs
    (version: String) => refs.filter(ref => supports.getOrElse(ref, Set.empty).contains(version))
  }

  /** Matches a per-project `scalaVersion`, `scalaHome`, or `scalaInstance` setting whose
    * project axis is in `affectedRefs`. Mirrors sbt's `Cross.setScalaVersionsForProjects`
    * filter shape exactly — restricted to `Scope(Select(ref), Zero, Zero, Zero)` so we
    * don't accidentally strip rawAppend entries for other projects, ThisBuild, or
    * config-scoped overrides.
    */
  private def isAffectedScalaSetting(s: Setting[?], affectedRefs: Set[ProjectRef]): Boolean =
    s.key match {
      case ScopedKey(Scope(Select(ref: ProjectRef), Zero, Zero, Zero), key)
          if (key == Keys.scalaVersion.key
            || key == Keys.scalaHome.key
            || key == Keys.scalaInstance.key)
            && affectedRefs.contains(ref) =>
        true
      case _ => false
    }

  /** Matches a `scalaVersion`, `scalaHome`, or `scalaInstance` setting at any
    * project/config axis with task=Zero. This is intentionally broader than sbt's stock
    * `Cross.setScalaVersionsForProjects` filter (which requires `Scope(Select(ref),
    * Zero, Zero, Zero)`): the broader shape lets the rebuild strip all scope variants
    * (`Global`, `ThisBuild`, project, `Test`, etc.) from the structure and then re-add
    * the slice from `session.mergeSettings`, so config-scoped overrides flow through
    * intact. Including `scalaInstance` (the only piece we strictly mirror from sbt's
    * filter) prevents builds that explicitly define a scoped `scalaInstance` from
    * keeping the old compiler instance after a switch.
    */
  private def isScalaSetting(s: Setting[?]): Boolean =
    s.key match {
      case ScopedKey(Scope(_, _, Zero, _), key)
          if key == Keys.scalaVersion.key
            || key == Keys.scalaHome.key
            || key == Keys.scalaInstance.key =>
        true
      case _ => false
    }
}
