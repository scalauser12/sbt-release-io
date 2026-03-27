package io.release.internal

import cats.effect.IO
import io.release.ReleaseKeys
import io.release.TestSupport
import munit.CatsEffectSuite

class ReleaseCommandRunnerSpec extends CatsEffectSuite {

  test("runSync - return the provided failure state on uncaught NonFatal") {
    TestSupport.tempDirResource("release-command-runner").use { dir =>
      IO {
        val initialState = TestSupport
          .dummyState(dir)
          .put(ReleaseKeys.versions, "0.1.0" -> "0.2.0-SNAPSHOT")
        val cleanState   = initialState.remove(ReleaseKeys.versions)
        val result       = ReleaseCommandRunner.runSync(cleanState, ReleaseLogPrefixes.Core)(
          IO.raiseError(new RuntimeException("boom"))
        )

        assertEquals(initialState.get(ReleaseKeys.versions), Some("0.1.0" -> "0.2.0-SNAPSHOT"))
        assertEquals(result.get(ReleaseKeys.versions), None)
      }
    }
  }
}
