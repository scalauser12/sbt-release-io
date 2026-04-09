package io.release

import _root_.sbt.{internal as _, *}
import _root_.sbt.Def.ScopedKey
import _root_.sbt.internal.BuildStructure

/** Internal sbt 2 compatibility layer for `_root_.sbt.Load`.
  * Delegates to a bridge in `package sbt`, which has access to `private[sbt]` internals.
  * Not a supported public extension point.
  */
private[release] object LoadCompat:

  def reapply(newSettings: Seq[Setting[?]], structure: BuildStructure)(using
      display: Show[ScopedKey[?]]
  ): BuildStructure =
    _root_.sbt.ReleaseIOLoadCompatBridge.reapply(newSettings, structure)
