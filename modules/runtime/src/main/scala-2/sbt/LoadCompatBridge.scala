package sbt

import scala.annotation.tailrec

/** Plugin-specific bridge in `package sbt` to access `private[sbt]` internals.
  * The name is intentionally scoped to sbt-release-io to avoid classpath collisions
  * with other plugins using the same bridge pattern.
  */
object ReleaseIOLoadCompatBridge {

  private final case class TrustedSessionStructure(
      session: AnyRef,
      structure: AnyRef,
      persistentPrefixLength: Int
  )

  private final case class StructurePartition(
      persistentPrefixLength: Int,
      transient: Seq[Setting[_]]
  )

  private final case class PersistentDefinitionShape(
      key: Def.ScopedKey[_],
      dependencies: Vector[Def.ScopedKey[_]],
      definitive: Boolean,
      position: Option[String]
  )

  private val PrefixInvariantMessage =
    "Cannot promote transient release settings: sbt structure.settings no longer " +
      "starts with the transformed session.mergeSettings prefix"

  private val trustedSessionStructureGuardToken: AnyRef = new Object

  private val trustedSessionStructureGuardKey: SettingKey[AnyRef] =
    SettingKey[AnyRef](
      "releaseIOInternalTrustedSessionStructureGuard",
      "Internal guard proving that an sbt structure was rebuilt from session.mergeSettings"
    )

  private val trustedSessionStructureGuardSetting: Setting[AnyRef] =
    Global / trustedSessionStructureGuardKey := trustedSessionStructureGuardToken

  private val trustedSessionStructureKey: AttributeKey[TrustedSessionStructure] =
    AttributeKey[TrustedSessionStructure]("releaseIOInternalTrustedSessionStructure")

  private def hasTrustedSessionStructureGuard(settings: Seq[Setting[_]]): Boolean =
    settings.exists(setting =>
      setting.asInstanceOf[AnyRef] eq trustedSessionStructureGuardSetting.asInstanceOf[AnyRef]
    )

  private def trustedSessionStructureGuardIsLive(
      structure: _root_.sbt.internal.BuildStructure
  ): Boolean =
    structure.data
      .getDirect(
        trustedSessionStructureGuardSetting.key.scope,
        trustedSessionStructureGuardKey.key
      )
      .exists(_ eq trustedSessionStructureGuardToken)

  private def guardSettingIfMissing(settings: Seq[Setting[_]]): Seq[Setting[_]] =
    if (hasTrustedSessionStructureGuard(settings)) Nil
    else trustedSessionStructureGuardSetting :: Nil

  /** Stamp a persistent definition with a private guard dependency. The dependency
    * survives sbt's setting transforms without changing the resolved value, giving
    * stale structures definition-level provenance that a fresh same-key replacement
    * does not carry.
    */
  private def withPersistentDefinitionProvenance[A](setting: Setting[A]): Setting[A] =
    if (
      setting.key == trustedSessionStructureGuardSetting.key ||
      setting.dependencies.contains(trustedSessionStructureGuardSetting.key)
    ) setting
    else
      setting.mapInitialize(
        _.zipWith(trustedSessionStructureGuardSetting.key)((value, _) => value)
      )

  private def withPersistentDefinitionProvenance(
      settings: Seq[Setting[_]]
  ): Seq[Setting[_]] =
    settings.map(setting => withPersistentDefinitionProvenance(setting))

  private def persistentDefinitionShape(
      setting: Setting[_]
  ): PersistentDefinitionShape =
    PersistentDefinitionShape(
      key = setting.key.asInstanceOf[Def.ScopedKey[_]],
      dependencies = setting.dependencies.toVector,
      definitive = setting.definitive,
      position = setting.positionString
    )

  private def hasExpectedPersistentPrefix(
      current: Seq[Setting[_]],
      expected: Seq[Setting[_]]
  ): Boolean =
    current.lengthCompare(expected.length) >= 0 &&
      current
        .take(expected.length)
        .map(persistentDefinitionShape) == expected.map(persistentDefinitionShape)

  private def trustedSessionStructure(state: State): Option[TrustedSessionStructure] =
    state.get(trustedSessionStructureKey)

  private def markTrustedSessionStructure(
      state: State,
      session: AnyRef,
      structure: AnyRef,
      persistentPrefixLength: Int
  ): State =
    state.put(
      trustedSessionStructureKey,
      TrustedSessionStructure(session, structure, persistentPrefixLength)
    )

  private def prefixInvariantFailure(): Nothing =
    throw new IllegalStateException(PrefixInvariantMessage)

  /** Split a structure into the transformed persistent session prefix and the
    * transient `appendWithSession` suffix. Exact bridge markers make this split
    * unambiguous even for bridge-controlled hybrid rebuilds; stale and unmarked
    * structures must match the session prefix's ordered definition provenance.
    * This accepts a legitimate empty `appendWithSession` rebuild while rejecting
    * a fresh same-key replacement whose definition lacks the private dependency.
    */
  private def structurePartition(
      state: State,
      extracted: Extracted
  )(implicit showKey: Show[Def.ScopedKey[_]]): StructurePartition = {
    import extracted._
    val current       = structure.settings
    val trustedMarker = trustedSessionStructure(state)

    trustedMarker.filter(marker =>
      (marker.session eq session) && (marker.structure eq structure)
    ) match {
      case Some(marker) if marker.persistentPrefixLength <= current.length =>
        StructurePartition(
          marker.persistentPrefixLength,
          current.drop(marker.persistentPrefixLength)
        )
      case Some(_)                                                         =>
        prefixInvariantFailure()
      case None                                                            =>
        val expectedPrefix =
          _root_.sbt.internal.Load.finalTransforms(session.mergeSettings)
        val guardIsValid   =
          !hasTrustedSessionStructureGuard(session.rawAppend) ||
            trustedSessionStructureGuardIsLive(structure)

        if (
          !hasExpectedPersistentPrefix(current, expectedPrefix) ||
          !guardIsValid
        ) prefixInvariantFailure()

        StructurePartition(
          expectedPrefix.length,
          current.drop(expectedPrefix.length)
        )
    }
  }

  private def markCanonicalSessionStructure(
      state: State,
      session: AnyRef,
      structure: _root_.sbt.internal.BuildStructure
  ): State =
    markTrustedSessionStructure(state, session, structure, structure.settings.length)

  /** Resolve `This` scopes against the current project, then run sbt's setting
    * injection — the same transformation `Extracted.appendWithSession` performs
    * before reapply. Required when settings carry unresolved scopes (e.g.
    * `version := "1.0.0"` — `version`'s scope is `This / version`).
    */
  def transformSettings(
      extracted: Extracted,
      settings: Seq[Setting[_]]
  ): Seq[Setting[_]] =
    _root_.sbt.internal.Load.transformSettings(
      _root_.sbt.internal.Load.projectScope(extracted.currentRef),
      extracted.currentRef.build,
      extracted.rootProject,
      settings
    )

  /** Install settings into `session.rawAppend` so they survive subsequent
    * `appendWithSession` calls.
    */
  def appendSessionSettings(state: State, settings: Seq[Setting[_]]): State = {
    val extracted                                = Project.extract(state)
    import extracted._
    implicit val showKey: Show[Def.ScopedKey[_]] = extracted.showKey
    val transformed                              = withPersistentDefinitionProvenance(
      transformSettings(extracted, settings)
    )
    val newSession                               = session.appendRaw(
      transformed ++ guardSettingIfMissing(session.rawAppend)
    )
    val newStructure                             = _root_.sbt.internal.Load.reapply(newSession.mergeSettings, structure)
    val newState                                 = Project.setProject(newSession, newStructure, state)
    markCanonicalSessionStructure(newState, newSession, newStructure)
  }

  /** Promote the transient `appendWithSession` suffix selected by attribute key,
    * including transient definitions that the selected settings depend on.
    */
  def promoteTransientSettingsByKey(
      state: State,
      keys: Seq[AttributeKey[_]]
  ): State = {
    val extracted                                = Project.extract(state)
    import extracted._
    implicit val showKey: Show[Def.ScopedKey[_]] = extracted.showKey
    val current                                  = structure.settings
    val trustedMarker                            = trustedSessionStructure(state)
    val exactTrustedStructure                    =
      trustedMarker.filter(marker => (marker.session eq session) && (marker.structure eq structure))
    if (exactTrustedStructure.exists(_.persistentPrefixLength == current.length)) state
    else {
      val transient        = structurePartition(state, extracted).transient
      val keySet           = keys.toSet
      // Match sbt's Init.grouped semantics: a definitive definition (`:=`)
      // discards every earlier definition for the same scoped key, while
      // non-definitive updates after it remain active. Following dependencies
      // from every historical definition would retain helpers referenced only
      // by a resolver that sbt itself has already shadowed.
      val definitionsByKey = current.foldLeft(
        Map.empty[Def.ScopedKey[_], Vector[Setting[_]]]
      ) { (acc, setting) =>
        val scopedKey         = setting.key.asInstanceOf[Def.ScopedKey[_]]
        val activeDefinitions =
          if (setting.definitive) Vector.empty[Setting[_]] :+ setting
          else acc.getOrElse(scopedKey, Vector.empty[Setting[_]]) :+ setting
        acc.updated(scopedKey, activeDefinitions)
      }
      val definedKeys      = definitionsByKey.keySet
      val rootKeys         = definedKeys.filter(key => keySet.contains(key.key))

      def effectiveDependency(key: Def.ScopedKey[_]): Option[Def.ScopedKey[_]] = {
        val scopes = (key.scope +: structure.delegates(key.scope)).distinct
        scopes.iterator
          .map(scope => Def.ScopedKey(scope, key.key))
          .find(definedKeys.contains)
      }

      def delegatedBase(key: Def.ScopedKey[_]): Option[Def.ScopedKey[_]] =
        structure
          .delegates(key.scope)
          .iterator
          .filterNot(_ == key.scope)
          .map(scope => Def.ScopedKey(scope, key.key))
          .find(definedKeys.contains)

      def activeDependencies(key: Def.ScopedKey[_]): List[Def.ScopedKey[_]] = {
        val active     = definitionsByKey.getOrElse(key, Vector.empty)
        val referenced = active.iterator
          .flatMap(_.dependencies)
          .flatMap(effectiveDependency)
        // A non-definitive `+=` / `~=` definition needs a base value. When no
        // definitive definition remains active at this exact scope, sbt obtains
        // that base from the first defined delegated scope.
        val inherited  = active.headOption
          .filterNot(_.definitive)
          .flatMap(_ => delegatedBase(key))
          .iterator
        (referenced ++ inherited).toList
      }

      @tailrec
      def dependencyClosure(
          pending: List[Def.ScopedKey[_]],
          visited: Set[Def.ScopedKey[_]],
          required: Set[Def.ScopedKey[_]]
      ): Set[Def.ScopedKey[_]] =
        pending match {
          case Nil                         => required
          case key :: tail if visited(key) => dependencyClosure(tail, visited, required)
          case key :: tail                 =>
            val dependencies = activeDependencies(key)
            dependencyClosure(
              dependencies ::: tail,
              visited + key,
              required + key
            )
        }

      val promotedKeys = dependencyClosure(rootKeys.toList, Set.empty, Set.empty)
      val promoted     = transient.filter { setting =>
        val scopedKey = setting.key.asInstanceOf[Def.ScopedKey[_]]
        promotedKeys.contains(scopedKey) && definitionsByKey
          .getOrElse(scopedKey, Vector.empty)
          .exists(active => active.asInstanceOf[AnyRef] eq setting.asInstanceOf[AnyRef])
      }
      if (promoted.isEmpty) state
      else {
        val persistentPromoted = withPersistentDefinitionProvenance(promoted)
        val newSession         = session.appendRaw(
          persistentPromoted ++ guardSettingIfMissing(session.rawAppend)
        )
        val newStructure       =
          _root_.sbt.internal.Load.reapply(newSession.mergeSettings, structure)
        val newState           = Project.setProject(newSession, newStructure, state)
        markCanonicalSessionStructure(newState, newSession, newStructure)
      }
    }
  }

  /** Rebuild a modified session while preserving the non-discarded transient
    * suffix in canonical prefix/suffix order. Cross-build switching uses this
    * path so later transient promotion can still identify its suffix safely.
    */
  def rebuildSessionPreservingTransientSettings(
      state: State,
      rawAppendStrip: Setting[_] => Boolean,
      addToRawAppend: Seq[Setting[_]],
      transientStrip: Setting[_] => Boolean
  ): State = {
    val extracted                                = Project.extract(state)
    import extracted._
    implicit val showKey: Show[Def.ScopedKey[_]] = extracted.showKey
    val retainedTransient                        =
      structurePartition(state, extracted).transient.filterNot(transientStrip)
    val filteredRawAppend                        = session.rawAppend.filterNot(rawAppendStrip)
    val updatedRawAppend                         =
      filteredRawAppend ++ withPersistentDefinitionProvenance(addToRawAppend)
    val newSession                               = session.copy(
      rawAppend = updatedRawAppend ++ guardSettingIfMissing(updatedRawAppend)
    )
    val persistent                               = newSession.mergeSettings
    val expectedPrefix                           =
      _root_.sbt.internal.Load.finalTransforms(persistent)
    val newStructure                             = _root_.sbt.internal.Load.reapply(
      persistent ++ retainedTransient,
      structure
    )

    if (
      !hasExpectedPersistentPrefix(newStructure.settings, expectedPrefix) ||
      !trustedSessionStructureGuardIsLive(newStructure)
    ) prefixInvariantFailure()

    val newState = Project.setProject(newSession, newStructure, state)
    markTrustedSessionStructure(
      newState,
      newSession,
      newStructure,
      expectedPrefix.length
    )
  }

  /** Strip every entry whose `AttributeKey` is in `keys` from
    * `session.rawAppend`, then reapply. Filters across all scope variants of
    * each key.
    */
  def clearRawAppendByKey(state: State, keys: Seq[AttributeKey[_]]): State = {
    val extracted                                = Project.extract(state)
    import extracted._
    implicit val showKey: Show[Def.ScopedKey[_]] = extracted.showKey
    val keySet                                   = keys.toSet
    val filteredRawAppend                        = session.rawAppend.filterNot(s => keySet.contains(s.key.key))
    if (filteredRawAppend.length == session.rawAppend.length) state
    else {
      val newSession   = session.copy(
        rawAppend = filteredRawAppend ++ guardSettingIfMissing(filteredRawAppend)
      )
      val newStructure = _root_.sbt.internal.Load.reapply(newSession.mergeSettings, structure)
      val newState     = Project.setProject(newSession, newStructure, state)
      markCanonicalSessionStructure(newState, newSession, newStructure)
    }
  }
}
