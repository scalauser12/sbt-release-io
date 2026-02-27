# sbt-release-io

[![Built with Claude Code](https://img.shields.io/badge/Built%20with-Claude%20Code-blueviolet?logo=anthropic)](https://claude.ai/claude-code)

An sbt plugin suite that wraps [sbt-release](https://github.com/sbt/sbt-release) with cats-effect IO for composable, resource-safe release automation.

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| [core](modules/core/README.md) | `sbt-release-io` | IO-based release plugin for single-project builds. Drop-in replacement for sbt-release with `Resource` lifecycle, cross-build checks, and typed context threading. |
| [monorepo](modules/monorepo/README.md) | `sbt-release-io-monorepo` | Monorepo extension with per-project versioning, git-based change detection, topological ordering, per-project failure isolation, and tagging strategies. |

## Quick Start

### Single project

In `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.1.0-SNAPSHOT")
```

```bash
sbt "releaseIO with-defaults"
```

### Monorepo

In `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.1.0-SNAPSHOT")
```

In `build.sbt`:

```scala
lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
```

```bash
sbt "releaseIOMonorepo with-defaults"
```

## Build & Test

```bash
sbt compile              # compile both modules
sbt test                 # run unit tests (specs2)
sbt scripted             # run all scripted integration tests
sbt core/test            # core unit tests only
sbt monorepo/test        # monorepo unit tests only
```

## Compatibility

- **sbt**: 1.x
- **Scala**: 2.12
- **sbt-release**: 1.4.0
- **cats-effect**: 3.6.3

## License

This project is licensed under the [Apache License 2.0](LICENSE).

## Acknowledgments

Built on top of [sbt-release](https://github.com/sbt/sbt-release) by the sbt organization.

Developed with the assistance of [Claude Code](https://claude.ai/claude-code) by Anthropic.
