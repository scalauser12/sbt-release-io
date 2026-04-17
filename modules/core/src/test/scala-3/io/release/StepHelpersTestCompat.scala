package io.release

import sbt.Keys.interactionService
import sbt.{Def, InteractionService, Setting}

private[release] object StepHelpersTestCompat:
  def interactionServiceSetting(service: => InteractionService): Setting[?] =
    interactionService := Def.uncached {
      service
    }
