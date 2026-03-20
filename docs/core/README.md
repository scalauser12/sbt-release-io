# sbt-release-io (core)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io_2.12_1.0)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io_2.12_1.0)

A cats-effect IO port of [sbt-release](https://github.com/sbt/sbt-release) for sbt, with composable error handling and resource safety.

## Contents

| Page | Topics |
| ---- | ------ |
| [Getting started](getting-started.md) | Features, installation, CLI usage, default release steps |
| [Configuration](configuration.md) | `build.sbt` settings, custom version file formats |
| [Customization](customization.md) | Custom steps, tasks/commands as steps, custom plugins, resource-aware API |
| [Typelevel libraries](typelevel.md) | HTTP, fs2, and other FP libraries in release steps |
| [Settings reference](reference.md) | All `releaseIO*` settings, version bump types |
| [Operations](operations.md) | Recovery/rollback, migration, testing, compatibility, execution model vs sbt-release |

Project overview and Maven coordinates: [README](../../modules/core/README.md) in the repository.
