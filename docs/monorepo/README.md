# sbt-release-io-monorepo

[![Maven Central](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)

A monorepo release plugin for sbt, extending [sbt-release-io (core)](../core/README.md) with per-project version files, per-project tags, change detection, topological ordering, and failure isolation.

## Start

- [Getting started](getting-started.md)
  Install the plugin, run the first `help` / `check` / `run`, and find the next pages to read.
- [First release walkthrough](walkthrough.md)
  Set up a small monorepo from scratch and run the first end-to-end release.
- [Selective release walkthrough](selective-release-walkthrough.md)
  Rehearse a hook-first selective release with change detection, downstream inclusion, and explicit selectors.

## Learn

- [Usage](usage.md)
  CLI grammar, selectors, flags, version overrides, and short command examples.
- [Configuration](configuration.md)
  Starter `build.sbt` patterns and configuration recipes.
- [Settings reference](reference.md)
  Exhaustive catalog of `releaseIOMonorepoSelection*`, `releaseIOMonorepoPolicy*`, `releaseIOMonorepoHooks*`, and shared core settings.
- [Concepts](concepts.md)
  Default release phases, validate/execute flow, failure isolation, and topological ordering.
- [Change detection](change-detection.md)
  Git diff semantics, downstream inclusion, shared paths, and custom detectors.
- [Tagging and versions](tagging-and-versions.md)
  Per-project tags, version files, and cross-build implications.

## Customize

- [Customization](customization.md)
  Hook-first customization, resource-aware custom plugins, and recipes.

## Operate

- [Recipes](recipes.md)
  Cross-build, CI/CD, local rehearsal, and targeted-project workflows.
- [Operations](operations.md)
  Rollback and recovery after a failed or partial monorepo release.

Core plugin docs (shared settings and single-project behavior): [../core/README.md](../core/README.md).

Maven artifact entry point: [README](../../modules/monorepo/README.md).
