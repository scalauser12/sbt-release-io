package io.release

import sbt.{internal => _, *}
import sbt.Def.ScopedKey
import sbt.Keys.{resolvedScoped, streams}
import sbt.internal.{
  BuildStreams,
  BuildStructure,
  BuildUtil,
  Index,
  KeyIndex,
  LoadedBuildUnit,
  StructureIndex
}

/** Compatibility layer for sbt.Load which was made private in sbt 1.0.
  * Inlines the `finalTransforms` and `structureIndex` methods that were previously
  * delegated to sbt-release's `Load`.
  * See: https://github.com/sbt/sbt/issues/3296#issuecomment-315218050
  */
private[release] object LoadCompat {

  /** Reevaluates settings after modifying them. Does not recompile or reload any build
    * components.
    */
  def reapply(newSettings: Seq[Setting[?]], structure: BuildStructure)(implicit
      display: Show[ScopedKey[?]]
  ): BuildStructure = {
    val transformed            = finalTransforms(newSettings)
    val (compiledMap, newData) =
      Def.makeWithCompiledMap(transformed)(using structure.delegates, structure.scopeLocal, display)
    val newIndex               = structureIndex(
      newData,
      transformed,
      index => BuildUtil(structure.root, structure.units, index, newData),
      structure.units
    )
    val newStreams             = BuildStreams.mkStreams(structure.units, structure.root, newData)
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

  // Inlined from sbt-release's LoadCompat / sbt.internal.Load.
  // Maps dependencies on the special tasks:
  // 1. the scope of 'streams' is the same as the defining key and has the task axis set to the
  //    defining key
  // 2. the defining key is stored on constructed tasks: used for error reporting
  // 3. resolvedScoped is replaced with the defining key as a value
  // Note: this must be idempotent.
  private def finalTransforms(ss: Seq[Setting[?]]): Seq[Setting[?]] = {
    def mapSpecial(to: ScopedKey[?])        = new (ScopedKey ~> ScopedKey) {
      def apply[T](key: ScopedKey[T]): ScopedKey[T] =
        if (key.key == streams.key)
          ScopedKey(
            Scope.fillTaskAxis(Scope.replaceThis(to.scope)(key.scope), to.key),
            key.key
          )
        else key
    }
    def setDefining[T]                      = (key: ScopedKey[T], value: T) =>
      value match {
        case tk: Task[t]      => setDefinitionKey(tk, key).asInstanceOf[T]
        case ik: InputTask[t] =>
          ik.mapTask(tk => setDefinitionKey(tk, key)).asInstanceOf[T]
        case _                => value
      }
    def setResolved(defining: ScopedKey[?]) = new (ScopedKey ~> Option) {
      def apply[T](key: ScopedKey[T]): Option[T] =
        key.key match {
          case resolvedScoped.key => Some(defining.asInstanceOf[T])
          case _                  => None
        }
    }
    ss.map(s =>
      s mapConstant setResolved(s.key) mapReferenced mapSpecial(s.key) mapInit setDefining
    )
  }

  private def structureIndex(
      data: Settings[Scope],
      settings: Seq[Setting[?]],
      extra: KeyIndex => BuildUtil[?],
      projects: Map[URI, LoadedBuildUnit]
  ): StructureIndex = {
    val keys                                        = Index.allKeys(settings)
    val attributeKeys                               = Index.attributeKeys(data) ++ keys.map(_.key)
    val scopedKeys                                  = keys ++ data.allKeys((s, k) => ScopedKey(s, k)).toVector
    val projectsMap                                 = projects.map { case (k, v) => k -> v.defined.keySet }
    val configsMap: Map[String, Seq[Configuration]] =
      projects.values.flatMap(bu => bu.defined.map { case (k, v) => (k, v.configurations) }).toMap
    val keyIndex                                    =
      KeyIndex(scopedKeys.toVector, projectsMap, configsMap)
    val aggIndex                                    =
      KeyIndex.aggregate(scopedKeys.toVector, extra(keyIndex), projectsMap, configsMap)
    new StructureIndex(
      Index.stringToKeyMap(attributeKeys),
      Index.taskToKeyMap(data),
      Index.triggers(data),
      keyIndex,
      aggIndex
    )
  }

  private def setDefinitionKey[T](tk: Task[T], key: ScopedKey[?]): Task[T] =
    if (isDummy(tk)) tk else Task(tk.info.set(Keys.taskDefinitionKey, key), tk.work)

  private def isDummy(t: Task[?]): Boolean =
    t.info.attributes.get(isDummyTask).getOrElse(false)

  private val isDummyTask = AttributeKey[Boolean](
    "is-dummy-task",
    "Internal: used to identify dummy tasks. sbt injects values for these tasks at the start of task execution.",
    Int.MaxValue
  )
}
