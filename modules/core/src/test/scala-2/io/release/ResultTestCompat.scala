package io.release

import sbt.Def.ScopedKey
import sbt.internal.Aggregation.KeyValue
import sbt.{Inc, Incomplete, Result, Value}

private[release] object ResultTestCompat {
  def aggregatedSuccess[T](values: Seq[Seq[T]]): Result[Seq[KeyValue[Seq[T]]]] =
    Value(
      values.map(value => KeyValue(null.asInstanceOf[ScopedKey[?]], value))
    )

  def aggregatedFailure[T](message: String): Result[Seq[KeyValue[Seq[T]]]] =
    Inc(
      Incomplete(
        node = None,
        tpe = Incomplete.Error,
        message = Some(message),
        causes = Nil,
        directCause = None
      )
    )
}
