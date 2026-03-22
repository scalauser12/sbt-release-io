package io.release.monorepo.steps

import io.release.ReleaseIOCompat
import sbt.{Setting, *}

import java.io.File

// Source-split because sbt 1 and sbt 2 expose different test task result types and caching needs.
private[steps] object MonorepoStepTestCompat:

  def successfulTestTaskSetting(marker: File): Setting[?] =
    Test / ReleaseIOCompat.testKey := {
      Def.uncached {
        sbt.IO.write(marker, "ran")
        sbt.protocol.testing.TestResult.Passed
      }
    }
