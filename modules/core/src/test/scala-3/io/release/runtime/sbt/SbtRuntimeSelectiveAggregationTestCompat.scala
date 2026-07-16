package io.release.runtime.sbt

import sbt.*

import java.util.concurrent.atomic.AtomicInteger

private[release] object SbtRuntimeSelectiveAggregationTestCompat:

  def countedTaskSetting(
      key: TaskKey[Unit],
      counter: AtomicInteger
  ): Setting[?] =
    key := Def.uncached(increment(counter))

  def countedTaskWithDependencySetting(
      key: TaskKey[Unit],
      dependency: TaskKey[Unit],
      counter: AtomicInteger
  ): Setting[?] =
    key := Def.uncached {
      val _ = (Global / dependency).value
      increment(counter)
    }

  def globalCountedTaskSetting(
      key: TaskKey[Unit],
      counter: AtomicInteger
  ): Setting[?] =
    Global / key := Def.uncached(increment(counter))

  private def increment(counter: AtomicInteger): Unit =
    counter.incrementAndGet()
    ()
