package io.release

import sbt.InteractionService
import sbt.Keys.interactionService
import sbt.Setting

private[release] object StepHelpersTestCompat {
  def interactionServiceSetting(service: => InteractionService): Setting[?] =
    interactionService := service
}
