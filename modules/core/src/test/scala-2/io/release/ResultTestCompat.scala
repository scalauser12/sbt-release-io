package io.release

import sbt.Def.ScopedKey
import sbt.Result
import sbt.Value
import sbt.internal.Aggregation.KeyValue

private[release] object ResultTestCompat {
  def aggregatedSuccess[T](values: Seq[Seq[T]]): Result[Seq[KeyValue[Seq[T]]]] =
    Value(
      values.map(value => KeyValue(null.asInstanceOf[ScopedKey[?]], value))
    )
}
