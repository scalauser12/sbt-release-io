# sbt-release-io

[![sbt-release-io](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io_2.12_1.0?label=sbt-release-io)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io_2.12_1.0)
[![sbt-release-io-monorepo](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0?label=sbt-release-io-monorepo)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)
[![CI](https://github.com/scalauser12/sbt-release-io/actions/workflows/ci.yml/badge.svg)](https://github.com/scalauser12/sbt-release-io/actions/workflows/ci.yml)
[![Built with Claude Code](https://img.shields.io/badge/Built%20with-Claude%20Code-blueviolet?logo=anthropic)](https://claude.ai/claude-code)

A cats-effect IO port of [sbt-release](https://github.com/sbt/sbt-release) for sbt, with composable error handling and resource safety.

## Documentation

Start with the plugin-specific onboarding guides:

- Single-project builds: [docs/core/getting-started.md](docs/core/getting-started.md)
- Monorepos: [docs/monorepo/getting-started.md](docs/monorepo/getting-started.md)
- Full docs index: [docs/README.md](docs/README.md)

## Modules

| Module | Artifact | README | Description |
|--------|----------|--------|-------------|
| [core](modules/core/README.md) | `sbt-release-io` | [docs/core](docs/core/README.md) | IO-based release plugin for single-project builds. Independent codebase porting sbt-release onto cats-effect IO with `Resource` lifecycle, cross-build validation, and typed context threading. |
| [monorepo](modules/monorepo/README.md) | `sbt-release-io-monorepo` | [docs/monorepo](docs/monorepo/README.md) | Monorepo extension with per-project versioning, git-based change detection, topological ordering, per-project failure isolation, and per-project tags. |

## Quick Start

### Single project

Install in `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.8.1")
```

The plugin activates automatically. Add `version.sbt` with `ThisBuild / version := "0.1.0-SNAPSHOT"`.

First command:

```bash
sbt "releaseIO check with-defaults"
```

Read next:

- [Core getting started](docs/core/getting-started.md)
- [Core hook-first walkthrough](docs/core/hook-first-walkthrough.md)
- [Core customization](docs/core/customization.md)

### Monorepo

Install in `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.8.1")
```

In `build.sbt`:

```scala
lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
```

By default, each subproject needs its own `version.sbt` containing `version := "0.1.0-SNAPSHOT"`.

First command:

```bash
sbt "releaseIOMonorepo check with-defaults"
```

Read next:

- [Monorepo getting started](docs/monorepo/getting-started.md)
- [Selective release walkthrough](docs/monorepo/selective-release-walkthrough.md)
- [Monorepo customization](docs/monorepo/customization.md)

For routine customization, prefer the grouped hook/policy settings
(`releaseIOPolicy*`, `releaseIOHooks*`, `releaseIOMonorepoPolicy*`,
`releaseIOMonorepoHooks*`) and leave the built-in process intact. For shared-resource or advanced
integration work, use custom plugins built around the hook and resource-hook APIs. The older flat
key names and lower-level step DSLs were removed in the breaking API cleanup; raw process override
is no longer part of the supported public surface.

For local rehearsal recipes, see [docs/core/recipes.md](docs/core/recipes.md) and
[docs/monorepo/recipes.md](docs/monorepo/recipes.md). For rollback and recovery, see
[docs/core/operations.md](docs/core/operations.md) and
[docs/monorepo/operations.md](docs/monorepo/operations.md).

## Build & Test

```bash
sbt compile              # compile both modules
sbt test                 # run unit tests (MUnit)
sbt -Dsbt.version=2.0.0-RC9 compile  # compile on sbt 2 / Scala 3 (version defined as Sbt2Version in build.sbt)
sbt -Dsbt.version=2.0.0-RC9 test     # run unit tests on sbt 2 / Scala 3
./bin/sbt2-clean test    # same sbt 2 test lane from a clean tracked snapshot (ignores local Metals/Bloop files)
./bin/sbt2-clean core/scripted
./bin/sbt2-clean monorepo/scripted
sbt scripted             # run all scripted integration tests
sbt core/test            # core unit tests only
sbt monorepo/test        # monorepo unit tests only
sbt scalafmtAll          # format Scala sources
sbt scalafmtSbt          # format .sbt and project/*.scala build files
sbt scalafmtCheckAll     # verify Scala source formatting
sbt scalafmtSbtCheck     # verify sbt/build file formatting
```

Use `./bin/sbt2-clean ...` for local sbt 2 verification if your checkout has generated IDE files such
as `project/metals.sbt` or `.bloop/`. CI runs on a clean checkout and can use plain
`sbt -Dsbt.version=2.0.0-RC9 ...`.

## Compatibility

- **sbt**: 1.12.3 and 2.0.0-RC9
- **Scala**: 2.12 (sbt 1) / Scala 3 (sbt 2) — plugin compile targets, not constraints on your project's Scala version
- **cats-effect**: 3.7.0
- **VCS**: Git only

## License

This project is licensed under the [Apache License 2.0](LICENSE).

## Acknowledgments

Ports [sbt-release](https://github.com/sbt/sbt-release) by the sbt organization onto cats-effect IO.

Developed with the assistance of [Claude Code](https://claude.ai/claude-code) by Anthropic.
