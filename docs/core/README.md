# sbt-release-io (core)

[![Maven Central (sbt 1 / Scala 2.12)](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io_2.12_1.0?label=sbt%201%20%2F%20Scala%202.12)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io_2.12_1.0)
[![Maven Central (sbt 2 / Scala 3)](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io_sbt2_3?label=sbt%202%20%2F%20Scala%203)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io_sbt2_3)

A cats-effect IO port of [sbt-release](https://github.com/sbt/sbt-release) for sbt, with composable error handling and resource safety.

## Start

- [Getting started](getting-started.md)
  Install the plugin, run the first `help` / `check` / `run`, and see the default built-in steps.
- [Customization walkthrough](customization-walkthrough.md)
  Rehearse a release locally using policy keys and lifecycle hooks.

## Learn

- [Configuration](configuration.md)
  Starter `build.sbt` patterns and common configuration recipes.
- [Settings reference](reference.md)
  Exhaustive catalog of `releaseIOBehavior*`, `releaseIODefaults*`, `releaseIOVersioning*`, `releaseIOVcs*`, `releaseIOPublish*`, `releaseIOPolicy*`, `releaseIOHooks*`, version bump types, and the CLI grammar.
- [Concepts](concepts.md)
  Validate/execute semantics, execution model, and how the plugin compares to sbt-release.
- [Typelevel libraries](typelevel.md)
  Practical examples of using fs2, HTTP clients, and other FP libraries inside release steps.

## Customize

- [Customization](customization.md)
  Policy and hook customization, resource-aware custom plugins, and recipes.

## Operate

- [Recipes](recipes.md)
  Cross-build, CI/CD, and local rehearsal workflows.
- [Operations](operations.md)
  Rollback and recovery after a failed or partial release.

Project overview and Maven coordinates: [README](../../modules/core/README.md) in the repository.
