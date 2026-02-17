package io.release

import sbt._
import sbt.Def.ScopedKey
import sbt.Keys.{resolvedScoped, streams}
import sbt.internal.{BuildStructure, BuildStreams, BuildUtil, ExtendableKeyIndex, Index, KeyIndex, Load, LoadedBuildUnit, StructureIndex}
import sbt.internal.util.{AttributeKey, Settings}
import sbt.librarymanagement.Configuration

/** Compatibility layer for sbt.Load which was made private in sbt 1.0.
  * Based on sbt-release's LoadCompat implementation.
  * See: https://github.com/sbt/sbt/issues/3296#issuecomment-315218050
  */
object LoadCompat {

  /** Reevaluates settings after modifying them. Does not recompile or reload any build components. */
  def reapply(newSettings: Seq[Setting[_]], structure: BuildStructure)(implicit
      display: Show[ScopedKey[_]]
  ): BuildStructure = {
    val transformed = finalTransforms(newSettings)
    val (compiledMap, newData) =
      Def.makeWithCompiledMap(transformed)(using structure.delegates, structure.scopeLocal, display)
    val newIndex = structureIndex(
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

  /** Map dependencies on special tasks. Must be idempotent. */
  def finalTransforms(ss: Seq[Setting[_]]): Seq[Setting[_]] = {
    def mapSpecial(to: ScopedKey[_]) = new (ScopedKey ~> ScopedKey) {
      def apply[T](key: ScopedKey[T]) =
        if (key.key == streams.key)
          ScopedKey(Scope.fillTaskAxis(Scope.replaceThis(to.scope)(key.scope), to.key), key.key)
        else key
    }
    def setDefining[T] = (key: ScopedKey[T], value: T) =>
      value match {
        case tk: Task[t] => setDefinitionKey(tk, key).asInstanceOf[T]
        case ik: InputTask[t] => ik.mapTask(tk => setDefinitionKey(tk, key)).asInstanceOf[T]
        case _ => value
      }
    def setResolved(defining: ScopedKey[_]) = new (ScopedKey ~> Option) {
      def apply[T](key: ScopedKey[T]): Option[T] =
        key.key match {
          case resolvedScoped.key => Some(defining.asInstanceOf[T])
          case _ => None
        }
    }
    ss.map(s => s mapConstant setResolved(s.key) mapReferenced mapSpecial(s.key) mapInit setDefining)
  }

  def structureIndex(
      data: Settings[Scope],
      settings: Seq[Setting[_]],
      extra: KeyIndex => BuildUtil[_],
      projects: Map[URI, LoadedBuildUnit]
  ): StructureIndex = {
    val keys = Index.allKeys(settings)
    val attributeKeys = Index.attributeKeys(data) ++ keys.map(_.key)
    val scopedKeys = keys ++ data.allKeys((s, k) => ScopedKey(s, k)).toVector
    val projectsMap = projects.map { case (k, v) => k -> v.defined.keySet }
    val configsMap: Map[String, Seq[Configuration]] =
      projects.values.flatMap(bu => bu.defined.map { case (k, v) => (k, v.configurations) }).toMap
    val keyIndex = keyIndexApply(scopedKeys.toVector, projectsMap, configsMap)
    val aggIndex = keyIndexAggregate(scopedKeys.toVector, extra(keyIndex), projectsMap, configsMap)
    new StructureIndex(
      Index.stringToKeyMap(attributeKeys),
      Index.taskToKeyMap(data),
      Index.triggers(data),
      keyIndex,
      aggIndex
    )
  }

  def setDefinitionKey[T](tk: Task[T], key: ScopedKey[_]): Task[T] =
    if (isDummy(tk)) tk else Task(tk.info.set(Keys.taskDefinitionKey, key), tk.work)

  private def isDummy(t: Task[_]): Boolean = t.info.attributes.get(isDummyTask).getOrElse(false)
  private val Invisible = Int.MaxValue
  private val isDummyTask = AttributeKey[Boolean](
    "is-dummy-task",
    "Internal: used to identify dummy tasks.  sbt injects values for these tasks at the start of task execution.",
    Invisible
  )

  private def keyIndexApply(
      known: Iterable[ScopedKey[_]],
      projects: Map[URI, Set[String]],
      configurations: Map[String, Seq[Configuration]]
  ): ExtendableKeyIndex = {
    try {
      // Try sbt 1.1+ signature with immutable collections
      val f = classOf[KeyIndex].getMethods.find { m =>
        m.getName == "apply" && m.getParameterCount == 3
      }.get
      f.invoke(null, known.toSet, projects, configurations).asInstanceOf[ExtendableKeyIndex]
    } catch {
      case _: Exception =>
        // Fallback: try calling KeyIndex.apply directly
        KeyIndex(known.toSet, projects, configurations)
    }
  }

  private def keyIndexAggregate(
      known: Iterable[ScopedKey[_]],
      extra: BuildUtil[_],
      projects: Map[URI, Set[String]],
      configurations: Map[String, Seq[Configuration]]
  ): ExtendableKeyIndex = {
    try {
      val f = classOf[KeyIndex].getMethods.find { m =>
        m.getName == "aggregate" && m.getParameterCount == 4
      }.get
      f.invoke(null, known.toSet, extra, projects, configurations).asInstanceOf[ExtendableKeyIndex]
    } catch {
      case _: Exception =>
        // Fallback: try calling KeyIndex.aggregate directly
        KeyIndex.aggregate(known.toSet, extra, projects, configurations)
    }
  }
}
