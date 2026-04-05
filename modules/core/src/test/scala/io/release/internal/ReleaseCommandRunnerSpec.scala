package io.release.internal

import cats.effect.IO
import io.release.ReleaseKeys
import io.release.TestSupport
import munit.CatsEffectSuite

class ReleaseCommandRunnerSpec extends CatsEffectSuite {

  test("runSync - return the provided failure state on uncaught NonFatal") {
    TestSupport.tempDirResource("release-command-runner").use { dir =>
      IO {
        val buffered     = TestSupport.bufferedState(dir)
        val initialState = buffered.state
          .put(ReleaseKeys.versions, "0.1.0" -> "0.2.0-SNAPSHOT")
        val cleanState   = initialState.remove(ReleaseKeys.versions)
        val failedState  = cleanState.fail
        val result       = ReleaseCommandRunner.runSync(cleanState, ReleaseLogPrefixes.Core)(
          IO.raiseError(new RuntimeException("boom"))
        )
        val log          = buffered.consoleBuffer.toString("UTF-8")

        assertEquals(initialState.get(ReleaseKeys.versions), Some("0.1.0" -> "0.2.0-SNAPSHOT"))
        assertEquals(result.get(ReleaseKeys.versions), None)
        assertEquals(result.next.getClass.getName, failedState.next.getClass.getName)
        assertEquals(result.remainingCommands, failedState.remainingCommands)
        assert(log.contains(s"${ReleaseLogPrefixes.Core} Release failed: boom"))
      }
    }
  }
}
