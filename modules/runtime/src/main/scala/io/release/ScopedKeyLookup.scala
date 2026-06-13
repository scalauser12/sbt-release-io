package io.release

import _root_.sbt.Def.ScopedKey
import _root_.sbt.internal.BuildStructure
import _root_.sbt.{internal as _, *}

/** Scoped-key presence lookup shared across the core and monorepo modules.
  *
  * Checks whether a `ScopedKey` (or any of its delegate scopes) is defined in a build
  * structure. The logic is version-neutral — unlike the genuinely sbt-version-specific
  * `LoadCompat.reapply` — so it lives in the shared source root rather than being duplicated
  * across the scala-2/scala-3 `LoadCompat` shims. Public for cross-module reuse; not a
  * supported end-user extension point.
  */
private[release] object ScopedKeyLookup {

  def containsScopedKey(
      state: State,
      scopedKey: ScopedKey[?]
  ): Boolean =
    containsScopedKey(Project.extract(state).structure, scopedKey)

  def containsScopedKey(
      structure: BuildStructure,
      scopedKey: ScopedKey[?]
  ): Boolean = {
    val definedKeys     = structure.settings.iterator.map(_.key).toSet
    val candidateScopes =
      Iterator.single(scopedKey.scope) ++ structure.delegates(scopedKey.scope).iterator

    candidateScopes.exists(scope => definedKeys.contains(ScopedKey(scope, scopedKey.key)))
  }
}
