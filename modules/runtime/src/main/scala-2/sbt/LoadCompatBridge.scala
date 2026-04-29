package sbt

/** Plugin-specific bridge in `package sbt` to access `private[sbt]` internals.
  * The name is intentionally scoped to sbt-release-io to avoid classpath collisions
  * with other plugins using the same bridge pattern.
  */
object ReleaseIOLoadCompatBridge {

  /** Resolve `This` scopes against the current project, then run sbt's setting
    * injection — the same transformation `Extracted.appendWithSession` performs
    * before reapply. Required when settings carry unresolved scopes (e.g.
    * `version := "1.0.0"` — `version`'s scope is `This / version`).
    */
  def transformSettings(
      extracted: Extracted,
      settings: Seq[Setting[_]]
  ): Seq[Setting[_]] =
    _root_.sbt.internal.Load.transformSettings(
      _root_.sbt.internal.Load.projectScope(extracted.currentRef),
      extracted.currentRef.build,
      extracted.rootProject,
      settings
    )

  /** Install settings into `session.rawAppend` so they survive subsequent
    * `appendWithSession` calls.
    */
  def appendSessionSettings(state: State, settings: Seq[Setting[_]]): State = {
    val extracted                                = Project.extract(state)
    import extracted._
    implicit val showKey: Show[Def.ScopedKey[_]] = extracted.showKey
    val transformed                              = transformSettings(extracted, settings)
    val newSession                               = session.appendRaw(transformed)
    val newStructure                             = _root_.sbt.internal.Load.reapply(newSession.mergeSettings, structure)
    Project.setProject(newSession, newStructure, state)
  }

  /** Strip every entry whose `AttributeKey` is in `keys` from
    * `session.rawAppend`, then reapply. Filters across all scope variants of
    * each key.
    */
  def clearRawAppendByKey(state: State, keys: Seq[AttributeKey[_]]): State = {
    val extracted                                = Project.extract(state)
    import extracted._
    implicit val showKey: Show[Def.ScopedKey[_]] = extracted.showKey
    val keySet                                   = keys.toSet
    val filteredRawAppend                        = session.rawAppend.filterNot(s => keySet.contains(s.key.key))
    if (filteredRawAppend.length == session.rawAppend.length) state
    else {
      val newSession   = session.copy(rawAppend = filteredRawAppend)
      val newStructure = _root_.sbt.internal.Load.reapply(newSession.mergeSettings, structure)
      Project.setProject(newSession, newStructure, state)
    }
  }
}
