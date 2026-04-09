package sbt

import _root_.sbt.Def.ScopedKey
import _root_.sbt.internal.BuildStructure

/** Plugin-specific bridge in `package sbt` to access `private[sbt]` `Load.reapply`.
  * The name is intentionally scoped to sbt-release-io to avoid classpath collisions
  * with other plugins using the same bridge pattern.
  */
object ReleaseIOLoadCompatBridge:

  def reapply(newSettings: Seq[Setting[?]], structure: BuildStructure)(using
      display: Show[ScopedKey[?]]
  ): BuildStructure =
    _root_.sbt.internal.Load.reapply(newSettings, structure)
