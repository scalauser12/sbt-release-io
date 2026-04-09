# Documentation

[sbt-release-io](https://github.com/scalauser12/sbt-release-io) is a cats-effect IO port of [sbt-release](https://github.com/sbt/sbt-release) for sbt, with composable error handling and resource safety.

## Choose your plugin

- [**Core (single-project)**](core/README.md)
  `sbt-release-io` for one build, one version file, and the single-project release flow.
- [**Monorepo**](monorepo/README.md)
  `sbt-release-io-monorepo` for per-project version files, change detection, per-project tags, and topological ordering.

## Start here

- New to the core plugin:
  [Getting started (core)](core/getting-started.md) and [Customization walkthrough](core/customization-walkthrough.md)
- New to the monorepo plugin:
  [Getting started (monorepo)](monorepo/getting-started.md),
  [First release walkthrough](monorepo/walkthrough.md), and
  [Selective release walkthrough](monorepo/selective-release-walkthrough.md)

## Need customization?

- Core hooks and custom plugins:
  [core/customization.md](core/customization.md)
- Monorepo hooks and custom plugins:
  [monorepo/customization.md](monorepo/customization.md)
- Rehearsal and CI recipes:
  [core/recipes.md](core/recipes.md) and [monorepo/recipes.md](monorepo/recipes.md)

## Need operational help?

- Rollback and recovery:
  [core/operations.md](core/operations.md) and [monorepo/operations.md](monorepo/operations.md)
- Repository build, test, and compatibility information:
  [../README.md](../README.md)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Releases

- [Canonical changelog](../CHANGELOG.md)

## Repository

Build, CI, and module overview: [../README.md](../README.md).
