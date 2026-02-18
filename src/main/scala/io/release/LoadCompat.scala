package io.release

import sbt._
import sbt.Def.ScopedKey
import sbt.internal.{BuildStructure, BuildStreams, BuildUtil}

/** Compatibility layer for sbt.Load which was made private in sbt 1.0.
  * Only `reapply` is reimplemented here because sbtrelease.Load.reapply uses the older
  * Def.make API and omits the `compiledMap` field from BuildStructure, which is required
  * by newer sbt versions. All other helpers delegate directly to sbtrelease.Load.
  * See: https://github.com/sbt/sbt/issues/3296#issuecomment-315218050
  */
object LoadCompat {

  /** Reevaluates settings after modifying them. Does not recompile or reload any build components. */
  def reapply(newSettings: Seq[Setting[_]], structure: BuildStructure)(implicit
      display: Show[ScopedKey[_]]
  ): BuildStructure = {
    val transformed = sbtrelease.Load.finalTransforms(newSettings)
    val (compiledMap, newData) =
      Def.makeWithCompiledMap(transformed)(using structure.delegates, structure.scopeLocal, display)
    val newIndex = sbtrelease.Load.structureIndex(
      newData,
      transformed,
      index => BuildUtil(structure.root, structure.units, index, newData),
      structure.units
    )
    val newStreams = BuildStreams.mkStreams(structure.units, structure.root, newData)
    new BuildStructure(
      units = structure.units,
      root = structure.root,
      settings = transformed,
      data = newData,
      index = newIndex,
      streams = newStreams,
      delegates = structure.delegates,
      scopeLocal = structure.scopeLocal,
      compiledMap = compiledMap
    )
  }
}
