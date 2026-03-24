# sbt-release-io-monorepo

[![Maven Central](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)

A monorepo release plugin for sbt, extending [sbt-release-io (core)](../core/README.md) with per-project versioning, change detection, topological ordering, and failure isolation.

## Contents

| Page | Topics |
| ---- | ------ |
| [Getting started](getting-started.md) | Features, installation, CLI usage |
| [Usage](usage.md) | CLI command, flags, version overrides, examples |
| [First release walkthrough](walkthrough.md) | End-to-end setup from scratch |
| [Concepts](concepts.md) | Default release steps, execution model, ordering, failure isolation |
| [Configuration](configuration.md) | All `releaseIOMonorepo*` and related settings |
| [Change detection](change-detection.md) | Git diff, downstream, overrides, custom detectors |
| [Tagging and versions](tagging-and-versions.md) | Per-project vs unified tags, global version mode, cross-build |
| [Customization](customization.md) | Custom steps, builder API, custom plugins, step timing |
| [Recipes](recipes.md) | Cross-build, CI/CD, local rehearsal |
| [Operations](operations.md) | Recovery, migration, testing, compatibility |

Core plugin docs (shared settings and single-project behavior): [../core/README.md](../core/README.md).

Maven artifact entry point: [README](../../modules/monorepo/README.md).
