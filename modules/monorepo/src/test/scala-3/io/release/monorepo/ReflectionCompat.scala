package io.release.monorepo

// Source-split to keep the reflection varargs bridge warning-free on both Scala 2 and Scala 3.
private[monorepo] object ReflectionCompat:

  def newInstance(
      constructor: java.lang.reflect.Constructor[AnyRef],
      values: Array[Object]
  ): AnyRef =
    constructor.newInstance(values*)
