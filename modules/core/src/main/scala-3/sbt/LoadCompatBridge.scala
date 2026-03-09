package sbt

import sbt.Def.ScopedKey
import sbt.internal.BuildStructure

/** Plugin-specific bridge in `package sbt` to access `private[sbt]` `Load.reapply`.
  * The name is intentionally scoped to sbt-release-io to avoid classpath collisions
  * with other plugins using the same bridge pattern.
  */
object ReleaseIOLoadCompatBridge:

  def reapply(newSettings: Seq[Setting[?]], structure: BuildStructure)(using
      display: Show[ScopedKey[?]]
  ): BuildStructure =
    sbt.internal.Load.reapply(newSettings, structure)
