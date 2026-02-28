package sbt

import sbt.Def.ScopedKey
import sbt.internal.BuildStructure

/** Bridge in `package sbt` to access `private[sbt]` Load.reapply.
  * Called by io.release.LoadCompat.
  *
  * TODO: verify sbt.internal.Load.reapply signature against actual sbt 2 release
  */
object LoadCompatBridge {

  def reapply(newSettings: Seq[Setting[?]], structure: BuildStructure)(using
      display: Show[ScopedKey[?]]
  ): BuildStructure =
    sbt.internal.Load.reapply(newSettings, structure)
}
