# sbt-release-io

[![Maven Central](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io_2.12_1.0)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io_2.12_1.0)

A cats-effect IO port of [sbt-release](https://github.com/sbt/sbt-release) for sbt, with composable error handling and resource safety.

## Features

Two-phase release steps (`validate` / `execute`) in cats-effect `IO`, hook-based lifecycle
customization, cross-build support, resource-safe custom plugins (`ReleasePluginIOLike[T]`),
optional interactive prompts, and configurable version files and VCS behavior.

## Quick start

`project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.9.0")
```

Add `version.sbt` with `ThisBuild / version := "0.1.0-SNAPSHOT"`. The plugin loads automatically
(`allRequirements`).

First command:

```bash
sbt "releaseIO check with-defaults"
```

Preferred customization keeps the built-in process intact and uses grouped
`releaseIOPolicy*` keys, `releaseIOHooks*`, and resource-aware custom plugins when one shared
resource is needed. The older flat key names and lower-level step DSL were removed in the
breaking API cleanup.

## Read next

- [Core getting started](../../docs/core/getting-started.md) for install, first `help` / `check` / `run`, and the default built-in steps
- [Core hook-first walkthrough](../../docs/core/hook-first-walkthrough.md) for a safe local rehearsal that keeps the built-in process intact
- [Core configuration](../../docs/core/configuration.md) for starter `build.sbt` patterns, and [Core reference](../../docs/core/reference.md) for the full settings and CLI catalog
- [Core customization](../../docs/core/customization.md) for hooks, resource-aware custom plugins, and migration guidance
- [Core operations](../../docs/core/operations.md) for rollback and recovery
- [Docs index](../../docs/README.md) for the full documentation tree

## License

This project is licensed under the [Apache License 2.0](../../LICENSE).
