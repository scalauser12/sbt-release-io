package io.release.internal

import scala.collection.mutable.ArrayBuffer

import sbt.{AttributeKey, SettingKey, Task, TaskKey}

private[release] object PublicKeyCatalogSupport {

  sealed trait KeyKind

  object KeyKind {
    case object Setting                         extends KeyKind
    final case class Task(isTransient: Boolean) extends KeyKind
  }

  final case class PublicEntry(
      group: String,
      label: String,
      description: String,
      kind: KeyKind,
      attributeKey: AttributeKey[?],
      keyRef: AnyRef
  )

  final class Builder {
    private val entries = ArrayBuffer.empty[PublicEntry]

    def setting[A: Manifest](
        group: String,
        label: String,
        description: String
    ): SettingKey[A] = {
      val key = SettingKey[A](AttributeKey[A](label, description))
      entries += PublicEntry(
        group = group,
        label = label,
        description = description,
        kind = KeyKind.Setting,
        attributeKey = key.key,
        keyRef = key
      )
      key
    }

    def task[A: Manifest](
        group: String,
        label: String,
        description: String,
        isTransient: Boolean = false
    ): TaskKey[A] = {
      val key = TaskKey[A](AttributeKey[Task[A]](label, description))
      entries += PublicEntry(
        group = group,
        label = label,
        description = description,
        kind = KeyKind.Task(isTransient),
        attributeKey = key.key,
        keyRef = key
      )
      key
    }

    def publicEntries: Vector[PublicEntry] =
      entries.toVector
  }
}
