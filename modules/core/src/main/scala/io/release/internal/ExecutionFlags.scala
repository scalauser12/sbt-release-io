package io.release.internal

/** Normalized execution flags shared by core and monorepo planners. */
private[release] final case class ExecutionFlags(
    useDefaults: Boolean,
    skipTests: Boolean,
    skipPublish: Boolean,
    interactive: Boolean,
    crossBuild: Boolean
)
