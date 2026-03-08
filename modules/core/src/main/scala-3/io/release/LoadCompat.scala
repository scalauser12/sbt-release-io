package io.release

import sbt.*
import sbt.Def.ScopedKey
import sbt.internal.BuildStructure

/** sbt 2 compatibility layer for sbt.Load.
  * Delegates to LoadCompatBridge (in `package sbt`) which has access to `private[sbt]` internals.
  * This mirrors sbt-release's own scala-3 LoadCompat approach.
  */
object LoadCompat {

  def reapply(newSettings: Seq[Setting[?]], structure: BuildStructure)(using
      display: Show[ScopedKey[?]]
  ): BuildStructure =
    sbt.LoadCompatBridge.reapply(newSettings, structure)
}
