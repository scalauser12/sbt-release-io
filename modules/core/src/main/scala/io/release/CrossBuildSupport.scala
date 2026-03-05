package io.release

import sbt.*
import sbt.Def.ScopedKey
import sbt.Keys.*

/** Shared cross-build utilities used by both the core [[ReleaseComposer]] and the
  * monorepo `MonorepoComposer`. Switching Scala versions requires a full project-structure
  * reload to invalidate incremental compilation caches.
  */
private[release] object CrossBuildSupport {

  /** Switch Scala version by fully reloading the project structure.
    * Based on `sbt.Cross.switchVersion` logic.
    */
  def switchScalaVersion(state: State, version: String): State = {
    val extracted = Project.extract(state)
    import extracted.*

    state.log.info(s"[release-io] Setting scala version to $version")

    val add = Seq(
      GlobalScope / Keys.scalaVersion := version,
      GlobalScope / Keys.scalaHome    := None
    )

    val cleared      = session.mergeSettings.filterNot(crossExclude)
    val newStructure = LoadCompat.reapply(add ++ cleared, structure)
    Project.setProject(session, newStructure, state)
  }

  /** Check if a setting should be excluded during cross-build (scalaVersion, scalaHome). */
  private[release] def crossExclude(s: Setting[?]): Boolean =
    s.key match {
      case ScopedKey(Scope(_, Zero, Zero, _), key)
          if key == Keys.scalaVersion.key || key == Keys.scalaHome.key =>
        true
      case _ => false
    }
}
