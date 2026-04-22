# sbt-release-io-monorepo

[![Maven Central (sbt 1 / Scala 2.12)](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0?label=sbt%201%20%2F%20Scala%202.12)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)
[![Maven Central (sbt 2 / Scala 3)](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io-monorepo_sbt2_3?label=sbt%202%20%2F%20Scala%203)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io-monorepo_sbt2_3)

A monorepo release plugin for sbt, built on top of
[sbt-release-io (core)](../core/README.md), with per-project version files, per-project tags,
change detection, topological ordering, and failure isolation.

## Start

- [Getting started](getting-started.md)
  Install the plugin, run the first `help` / `check` / `run`, and find the next pages to read.
- [First release walkthrough](walkthrough.md)
  Set up a small monorepo from scratch and run the first end-to-end release.
- [Selective release walkthrough](selective-release-walkthrough.md)
  Rehearse a selective release with change detection, downstream inclusion, and explicit selectors.

## Learn

- [Usage](usage.md)
  CLI grammar, selectors, flags, version overrides, and short command examples.
- [Configuration](configuration.md)
  Starter `build.sbt` patterns and configuration recipes.
- [Settings reference](reference.md)
  Exhaustive catalog of `releaseIOMonorepoSelection*`, `releaseIOMonorepoPolicy*`, `releaseIOMonorepoHooks*`, and shared release settings.
- [Concepts](concepts.md)
  Default release phases, validate/execute flow, failure isolation, and topological ordering.
- [Change detection](change-detection.md)
  Git diff semantics, downstream inclusion, shared paths, and custom detectors.
- [Tagging and versions](tagging-and-versions.md)
  Per-project tags, version files, and cross-build implications.

## Customize

- [Customization](customization.md)
  Policy and hook customization, resource-aware custom plugins, and recipes.

## Operate

- [Recipes](recipes.md)
  Cross-build, CI/CD, local rehearsal, and targeted-project workflows.
- [Operations](operations.md)
  Rollback and recovery after a failed or partial monorepo release.

Core plugin docs (shared settings and single-project behavior): [../core/README.md](../core/README.md).

> **Note:** This page describes the current published monorepo contract in `v0.11.1`; see
> [CHANGELOG.md](../../CHANGELOG.md) for the full release history.

Monorepo installs inherit the shared/core `releaseIO*` settings surface transitively, while
`MonorepoReleasePlugin.autoImport` remains the grouped surface for `releaseIOMonorepo*`.
Use `releaseIOMonorepo` as the release command in the documented monorepo setup.

Maven artifact entry point: [README](../../modules/monorepo/README.md).
