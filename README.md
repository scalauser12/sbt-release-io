# sbt-release-io

[![sbt-release-io](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io_2.12_1.0?label=sbt-release-io)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io_2.12_1.0)
[![sbt-release-io-monorepo](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0?label=sbt-release-io-monorepo)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)
[![CI](https://github.com/scalauser12/sbt-release-io/actions/workflows/ci.yml/badge.svg)](https://github.com/scalauser12/sbt-release-io/actions/workflows/ci.yml)
[![Built with Claude Code](https://img.shields.io/badge/Built%20with-Claude%20Code-blueviolet?logo=anthropic)](https://claude.ai/claude-code)

A cats-effect IO alternative to [sbt-release](https://github.com/sbt/sbt-release), with
hook-based customization, monorepo support, and `Resource`-safe lifecycle management. It uses
`releaseIO` commands and policy/hook/resource APIs rather than sbt-release's commands and process
editing, and it supports Git only. See the
[execution-model comparison](docs/core/concepts.md#execution-model-sbt-release-io-vs-sbt-release)
for more differences.

## Documentation

Start with the plugin-specific onboarding guides:

- Single-project builds: [docs/core/getting-started.md](docs/core/getting-started.md)
- Monorepos: [docs/monorepo/getting-started.md](docs/monorepo/getting-started.md)
- Full docs index: [docs/README.md](docs/README.md)
- Release history and upgrade notes: [CHANGELOG.md](CHANGELOG.md)

## Modules

| Module | Artifact | Docs | Description |
|--------|----------|------|-------------|
| [core](modules/core/README.md) | `sbt-release-io` | [docs/core](docs/core/README.md) | Single-project releases with hook-based customization, cross-build validation, and `Resource`-safe lifecycle management. |
| [monorepo](modules/monorepo/README.md) | `sbt-release-io-monorepo` | [docs/monorepo](docs/monorepo/README.md) | Monorepo extension with per-project versioning, git-based change detection, topological ordering, per-project failure isolation, and per-project tags. |

## Quick Start

### Single project

Install in `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.13.6")
```

The plugin auto-enables on all projects. Add a `version.sbt`:

```scala
ThisBuild / version := "0.1.0-SNAPSHOT"
```

First command:

```bash
sbt "releaseIO check with-defaults"
```

Read next:

- [Core getting started](docs/core/getting-started.md)
- [Core customization walkthrough](docs/core/customization-walkthrough.md)
- [Core customization](docs/core/customization.md)

### Monorepo

Install in `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.13.6")
```

In `build.sbt`:

```scala
lazy val core = (project in file("core"))
lazy val api  = (project in file("api"))

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
```

Each subproject needs its own `version.sbt` containing `version := "0.1.0-SNAPSHOT"`.
The monorepo plugin depends on the core plugin, so the shared `releaseIO*` keys are
available transitively. Use `releaseIOMonorepo` to drive a release.

First command:

```bash
sbt "releaseIOMonorepo check with-defaults"
```

Read next:

- [Monorepo getting started](docs/monorepo/getting-started.md)
- [Selective release walkthrough](docs/monorepo/selective-release-walkthrough.md)
- [Monorepo customization](docs/monorepo/customization.md)

Customization is layered. Pick the lowest level that solves your problem:

- **CLI flags** for one-off tweaks: `with-defaults`, `skip-tests`, `cross`
- **Behavior toggles** to flip defaults: `releaseIOBehavior*` / `releaseIOMonorepoBehavior*`
- **Pre-answered prompts** for non-interactive runs: `releaseIODefaults*`
- **Task overrides** to redefine a specific step: `releaseIOPublishAction`,
  `releaseIOVcsTagName`, the `releaseIOVersioning*` keys
- **Hooks and policies** to inject logic around phases: `releaseIOHooks*` /
  `releaseIOPolicy*` (and their `releaseIOMonorepoHooks*` / `releaseIOMonorepoPolicy*`
  counterparts)
- **Resource hooks** for anything acquired once per run (HTTP client, temp workspace,
  etc.): extend `ReleasePluginIOLike[T]` / `MonorepoReleasePluginLike[T]`

See [docs/core/customization.md](docs/core/customization.md) and
[docs/monorepo/customization.md](docs/monorepo/customization.md) for the full catalog.

For local rehearsal recipes, see [docs/core/recipes.md](docs/core/recipes.md) and
[docs/monorepo/recipes.md](docs/monorepo/recipes.md). For rollback and recovery, see
[docs/core/operations.md](docs/core/operations.md) and
[docs/monorepo/operations.md](docs/monorepo/operations.md).

## Build & Test

Building this repository requires JDK 17 or newer because sbt 2 is the default launcher.

```bash
# sbt 1 / Scala 2.12 compatibility lane (version pinned in project/sbt1.version)
sbt --server --sbt-version 1.12.3 compile
sbt --server --sbt-version 1.12.3 test
sbt --server --sbt-version 1.12.3 core/test
sbt --server --sbt-version 1.12.3 monorepo/test
sbt --server --sbt-version 1.12.3 scripted

# sbt 2 / Scala 3 default lane (pinned in project/build.properties and project/sbt2.version)
sbt --server --sbt-version 2.0.0 compile  # direct sbt 2 lane
sbt --server --sbt-version 2.0.0 test
./bin/sbt2-clean test                # same lane from a filtered current-working-tree copy
./bin/sbt2-clean core/scripted       # core scripted tests on sbt 2
./bin/sbt2-clean monorepo/scripted   # monorepo scripted tests on sbt 2

# Formatting
sbt scalafmtAll          # format Scala sources
sbt scalafmtSbt          # format .sbt and project/*.scala build files
sbt scalafmtCheckAll     # verify Scala source formatting
sbt scalafmtSbtCheck     # verify sbt/build file formatting
```

The explicit compatibility commands use `--server --sbt-version` so the sbt 1 override wins
even though `project/build.properties` selects sbt 2 and enables its native client by default.
Plain `sbt ...` uses sbt 2.0.0 from `project/build.properties`. Prefer
`./bin/sbt2-clean ...` for local sbt 2 verification: it copies the current working tree to a
temporary directory while filtering known generated IDE/build files such as
`project/metals.sbt` and `.bloop/`. Non-excluded untracked files are included. CI runs on a clean
checkout and uses the explicitly pinned sbt version for each lane.

## Compatibility

- **sbt**: 1.12.3 and 2.0.0
- **Scala**: 2.12 (sbt 1) / Scala 3 (sbt 2) — plugin compile targets, not constraints on your project's Scala version
- **cats-effect**: 3.7.0
- **VCS**: Git only

## License

This project is licensed under the [Apache License 2.0](LICENSE).

## Acknowledgments

Ports [sbt-release](https://github.com/sbt/sbt-release) by the sbt organization onto cats-effect IO.

Developed with the assistance of [Claude Code](https://claude.ai/claude-code) by Anthropic.
