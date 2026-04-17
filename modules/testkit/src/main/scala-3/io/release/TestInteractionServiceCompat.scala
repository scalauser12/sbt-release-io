package io.release

import sbt.Keys.interactionService
import sbt.{Def, InteractionService, Setting}

/** Cross-build helper for installing an `InteractionService` task value in a synthetic test
  * project. The `:=` macro expects different body shapes on sbt 1 (Scala 2) and sbt 2
  * (Scala 3), so the actual setting construction is split between `scala-2/` and `scala-3/`.
  */
object TestInteractionServiceCompat:
  def interactionServiceSetting(service: => InteractionService): Setting[?] =
    interactionService := Def.uncached {
      service
    }
