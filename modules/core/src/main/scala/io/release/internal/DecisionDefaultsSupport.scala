package io.release.internal

import sbt.State

private[release] object DecisionDefaultsSupport {

  def renderYesNo(value: Boolean): String =
    if (value) "y" else "n"

  def resolveLast[A](
      state: State,
      prefix: String,
      argName: String,
      matches: Seq[A],
      render: A => String,
      warnOnDuplicates: Boolean = true
  ): Option[A] = {
    val selected = matches.lastOption

    if (warnOnDuplicates && matches.size > 1)
      state.log.warn(
        s"$prefix Multiple $argName args provided; using '${selected.map(render).getOrElse("<unknown>")}'"
      )

    selected
  }
}
