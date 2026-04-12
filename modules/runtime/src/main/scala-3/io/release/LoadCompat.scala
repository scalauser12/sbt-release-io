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

  def containsScopedKey(
      state: State,
      scopedKey: ScopedKey[?]
  ): Boolean =
    containsScopedKey(Project.extract(state).structure, scopedKey)

  def containsScopedKey(
      structure: BuildStructure,
      scopedKey: ScopedKey[?]
  ): Boolean =
    val definedKeys     = structure.settings.iterator.map(_.key).toSet
    val candidateScopes =
      Iterator.single(scopedKey.scope) ++ structure.delegates(scopedKey.scope).iterator

    candidateScopes.exists(scope => definedKeys.contains(ScopedKey(scope, scopedKey.key)))
