package sbt

/** Plugin-specific bridge in `package sbt` to access `private[sbt]` internals
  * for the testkit. Living in `package sbt` also brings sbt 2's `setProject`
  * extension into scope automatically. Mirrors the runtime bridge but kept
  * separate so testkit does not need a runtime dependency. The name is
  * intentionally scoped to sbt-release-io to avoid classpath collisions with
  * other plugins using the same bridge pattern.
  */
object ReleaseIOTestkitSbtBridge:

  /** Install settings into `session.rawAppend` so they survive subsequent
    * `appendWithSession` calls. See
    * `_root_.io.release.runtime.sbt.SbtRuntime.appendSessionSettings` for
    * the full contract.
    */
  def appendSessionSettings(state: State, settings: Seq[Setting[?]]): State =
    val extracted = Project.extract(state)
    import extracted.*
    given Show[Def.ScopedKey[?]] = extracted.showKey
    val transformed = _root_.sbt.internal.Load.transformSettings(
      _root_.sbt.internal.Load.projectScope(extracted.currentRef),
      extracted.currentRef.build,
      extracted.rootProject,
      settings
    )
    val newSession   = session.appendRaw(transformed)
    val newStructure = _root_.sbt.internal.Load.reapply(newSession.mergeSettings, structure)
    Project.setProject(newSession, newStructure, state)
