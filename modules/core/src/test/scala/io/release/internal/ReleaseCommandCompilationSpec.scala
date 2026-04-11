package io.release.runtime.command

import _root_.sbt.State
import cats.effect.IO
import io.release.ReleaseKeys
import io.release.TestSupport
import io.release.runtime.ReleaseLogPrefixes
import munit.CatsEffectSuite

class ReleaseCommandCompilationSpec extends CatsEffectSuite {

  test("runPreparedCommand - use the original state when cleanState throws") {
    TestSupport.tempDirResource("release-command-compilation-clean").use { dir =>
      IO {
        val buffered     = TestSupport.bufferedState(dir)
        val initialState = buffered.state.put(ReleaseKeys.versions, "0.1.0" -> "0.2.0-SNAPSHOT")
        val expected     = initialState.fail
        val result       = ReleaseCommandCompilation.runPreparedCommand[Unit](
          state = initialState,
          cleanState = _ => throw new RuntimeException("clean failed"),
          logPrefix = ReleaseLogPrefixes.Core
        )(
          prepare = _ => IO.pure(Right(())),
          run = _ => IO.pure(initialState)
        )
        val log          = buffered.consoleBuffer.toString("UTF-8")

        assertEquals(result.get(ReleaseKeys.versions), Some("0.1.0" -> "0.2.0-SNAPSHOT"))
        assertFailedStateLike(result, expected)
        assert(log.contains(s"${ReleaseLogPrefixes.Core} Release failed: clean failed"))
      }
    }
  }

  test("runPreparedCommand - use the cleaned state when prepare throws before returning IO") {
    TestSupport.tempDirResource("release-command-compilation-prepare").use { dir =>
      IO {
        val buffered     = TestSupport.bufferedState(dir)
        val initialState = buffered.state.put(ReleaseKeys.versions, "0.1.0" -> "0.2.0-SNAPSHOT")
        val cleanedState = initialState.remove(ReleaseKeys.versions)
        val expected     = cleanedState.fail
        val result       = ReleaseCommandCompilation.runPreparedCommand[Unit](
          state = initialState,
          cleanState = _.remove(ReleaseKeys.versions),
          logPrefix = ReleaseLogPrefixes.Core
        )(
          prepare = _ => throw new RuntimeException("prepare failed"),
          run = _ => IO.raiseError(new AssertionError("run should not execute"))
        )
        val log          = buffered.consoleBuffer.toString("UTF-8")

        assertEquals(result.get(ReleaseKeys.versions), None)
        assertFailedStateLike(result, expected)
        assert(log.contains(s"${ReleaseLogPrefixes.Core} Release failed: prepare failed"))
      }
    }
  }

  private def assertFailedStateLike(actual: State, expected: State): Unit = {
    assertEquals(actual.next.getClass.getName, expected.next.getClass.getName)
    assertEquals(actual.remainingCommands, expected.remainingCommands)
    assertEquals(actual.onFailure, expected.onFailure)
  }
}
