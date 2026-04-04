package io.release.internal

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

  final case class SettingDefinition[A](
      key: SettingKey[A],
      publicEntry: PublicEntry
  )

  final case class TaskDefinition[A](
      key: TaskKey[A],
      publicEntry: PublicEntry
  )

  def setting[A: Manifest](
      group: String,
      label: String,
      description: String
  ): SettingDefinition[A] = {
    val key = SettingKey[A](AttributeKey[A](label, description))
    SettingDefinition(
      key = key,
      publicEntry = PublicEntry(
        group = group,
        label = label,
        description = description,
        kind = KeyKind.Setting,
        attributeKey = key.key,
        keyRef = key
      )
    )
  }

  def task[A: Manifest](
      group: String,
      label: String,
      description: String,
      isTransient: Boolean = false
  ): TaskDefinition[A] = {
    val key = TaskKey[A](AttributeKey[Task[A]](label, description))
    TaskDefinition(
      key = key,
      publicEntry = PublicEntry(
        group = group,
        label = label,
        description = description,
        kind = KeyKind.Task(isTransient),
        attributeKey = key.key,
        keyRef = key
      )
    )
  }
}
