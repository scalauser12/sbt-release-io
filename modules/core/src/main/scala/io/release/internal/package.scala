package io.release

/** Internal infrastructure shared between the core and monorepo modules.
  *
  * Types in this package use `private[release]` scope, making them accessible
  * to `io.release.monorepo` but invisible to end users. The following types
  * form the cross-module contract:
  *
  *  - [[ExecutionEngine]] — two-phase validate-then-execute orchestration
  *  - [[ExecutionFlags]] — normalized command-line flags
  *  - [[InternalKeys]] — sbt State attribute keys for flags and plan
  *  - [[SbtRuntime]] — wrappers over sbt extraction/task/command APIs
  *  - [[SbtCompat]] — sbt version compatibility shims
  *  - [[CoreReleasePlan]] — typed startup plan (core-only, not used by monorepo)
  */
package object internal
