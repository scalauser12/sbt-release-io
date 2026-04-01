package io.release

import sbt.{internal as _, *}

object TestkitSbtCompat:

  def extract(state: State): Extracted =
    Project.extract(state)
