# sbt-release-io

[![Maven Central](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io_2.12_1.0)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io_2.12_1.0)

A cats-effect IO port of [sbt-release](https://github.com/sbt/sbt-release) for sbt, with composable error handling and resource safety.

## Features

Two-phase release steps (`validate` / `execute`) in cats-effect `IO`, cross-build support, resource-safe custom plugins (`ReleasePluginIOLike[T]`), optional interactive prompts, and configurable version files and VCS behavior.

## Quick start

`project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.6.0")
```

Add `version.sbt` with `ThisBuild / version := "0.1.0-SNAPSHOT"`. The plugin loads automatically (`allRequirements`).

```bash
sbt "releaseIO with-defaults"
```

## Documentation

Full guide: **[Documentation](../../docs/core/README.md)** (getting started, configuration, custom steps, settings reference, operations).

Top-level index: [docs/README.md](../../docs/README.md).

## License

This project is licensed under the [Apache License 2.0](../../LICENSE).
