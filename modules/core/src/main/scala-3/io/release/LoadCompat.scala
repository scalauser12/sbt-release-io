package io.release

import sbt.{internal => _, *}
import sbt.Def.ScopedKey
import sbt.internal.BuildStructure

/** Internal sbt 2 compatibility layer for `sbt.Load`.
  * Delegates to a bridge in `package sbt`, which has access to `private[sbt]` internals.
  * Not a supported public extension point.
  */
private[release] object LoadCompat:

  def reapply(newSettings: Seq[Setting[?]], structure: BuildStructure)(using
      display: Show[ScopedKey[?]]
  ): BuildStructure =
    sbt.ReleaseIOLoadCompatBridge.reapply(newSettings, structure)
