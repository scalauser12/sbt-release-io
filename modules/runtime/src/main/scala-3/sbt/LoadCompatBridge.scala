package sbt

import _root_.sbt.Def.ScopedKey
import _root_.sbt.internal.BuildStructure

/** Plugin-specific bridge in `package sbt` to access `private[sbt]` internals.
  * Living in `package sbt` also brings sbt 2's `setProject` extension into scope
  * automatically. The name is intentionally scoped to sbt-release-io to avoid
  * classpath collisions with other plugins using the same bridge pattern.
  */
object ReleaseIOLoadCompatBridge:

  def reapply(newSettings: Seq[Setting[?]], structure: BuildStructure)(using
      display: Show[ScopedKey[?]]
  ): BuildStructure =
    _root_.sbt.internal.Load.reapply(newSettings, structure)

  /** Resolve `This` scopes against the current project, then run sbt's setting
    * injection — the same transformation `Extracted.appendWithSession` performs
    * before reapply. Required when settings carry unresolved scopes (e.g.
    * `version := "1.0.0"` — `version`'s scope is `This / version`).
    */
  def transformSettings(
      extracted: Extracted,
      settings: Seq[Setting[?]]
  ): Seq[Setting[?]] =
    _root_.sbt.internal.Load.transformSettings(
      _root_.sbt.internal.Load.projectScope(extracted.currentRef),
      extracted.currentRef.build,
      extracted.rootProject,
      settings
    )

  /** Install settings into `session.rawAppend` so they survive subsequent
    * `appendWithSession` calls. See
    * `_root_.io.release.runtime.sbt.SbtRuntime.appendSessionSettings` for
    * the full contract.
    */
  def appendSessionSettings(state: State, settings: Seq[Setting[?]]): State =
    val extracted = Project.extract(state)
    import extracted.*
    given Show[ScopedKey[?]] = extracted.showKey
    val transformed  = transformSettings(extracted, settings)
    val newSession   = session.appendRaw(transformed)
    val newStructure = _root_.sbt.internal.Load.reapply(newSession.mergeSettings, structure)
    Project.setProject(newSession, newStructure, state)

  /** Strip every entry whose `AttributeKey` is in `keys` from
    * `session.rawAppend`, then reapply. Used to clear settings previously
    * installed via [[appendSessionSettings]] so they no longer reappear when
    * a later `appendSessionSettings` rebuilds the structure from
    * `session.mergeSettings`. Filters across all scope variants of each key.
    */
  def clearRawAppendByKey(state: State, keys: Seq[AttributeKey[?]]): State =
    val extracted = Project.extract(state)
    import extracted.*
    given Show[ScopedKey[?]] = extracted.showKey
    val keySet            = keys.toSet
    val filteredRawAppend = session.rawAppend.filterNot(s => keySet.contains(s.key.key))
    if filteredRawAppend.length == session.rawAppend.length then state
    else
      val newSession   = session.copy(rawAppend = filteredRawAppend)
      val newStructure = _root_.sbt.internal.Load.reapply(newSession.mergeSettings, structure)
      Project.setProject(newSession, newStructure, state)
