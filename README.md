# sbt-release-io

[![sbt-release-io](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io_2.12_1.0?label=sbt-release-io)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io_2.12_1.0)
[![sbt-release-io-monorepo](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0?label=sbt-release-io-monorepo)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)
[![CI](https://github.com/scalauser12/sbt-release-io/actions/workflows/ci.yml/badge.svg)](https://github.com/scalauser12/sbt-release-io/actions/workflows/ci.yml)
[![Built with Claude Code](https://img.shields.io/badge/Built%20with-Claude%20Code-blueviolet?logo=anthropic)](https://claude.ai/claude-code)

A cats-effect IO port of [sbt-release](https://github.com/sbt/sbt-release) for sbt, with composable error handling and resource safety.

## Documentation

User guides and reference: **[docs/README.md](docs/README.md)** (core and monorepo topics with cross-links).

## Modules

| Module | Artifact | README | Description |
|--------|----------|--------|-------------|
| [core](modules/core/README.md) | `sbt-release-io` | [docs/core](docs/core/README.md) | IO-based release plugin for single-project builds. Independent codebase porting sbt-release onto cats-effect IO with `Resource` lifecycle, cross-build validation, and typed context threading. |
| [monorepo](modules/monorepo/README.md) | `sbt-release-io-monorepo` | [docs/monorepo](docs/monorepo/README.md) | Monorepo extension with per-project versioning, git-based change detection, topological ordering, per-project failure isolation, and tagging strategies. |

## Quick Start

### Single project

In `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.6.0")
```

No `enablePlugins` needed — the core plugin activates automatically.

The project needs a `version.sbt` file containing `ThisBuild / version := "0.1.0-SNAPSHOT"`.

Start by inspecting the command help:

```bash
sbt "releaseIO help"
```

Run a preflight with no release side effects:

```bash
sbt "releaseIO check with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

`check` has no release side effects: no version-file writes, commits, tags, publish, or push. With cross-build validation enabled, sbt may temporarily switch Scala versions during validation and then restore the entry version.

Run the actual release (versions computed from `version.sbt`):

```bash
sbt "releaseIO with-defaults"
```

Or specify versions explicitly:

```bash
sbt "releaseIO with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

### Monorepo

In `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.6.0")
```

In `build.sbt`:

```scala
lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
```

By default, each subproject needs a `version.sbt` containing `version := "0.1.0-SNAPSHOT"`.

Start by inspecting the command help:

```bash
sbt "releaseIOMonorepo help"
```

Run a preflight with no release side effects:

```bash
sbt "releaseIOMonorepo check core with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"
```

`check` has no release side effects: no version-file writes, commits, tags, publish, or push. With cross-build validation enabled, sbt may temporarily switch Scala versions during validation and then restore the entry version.

Run the actual release (versions computed from each subproject's `version.sbt`):

```bash
sbt "releaseIOMonorepo with-defaults"
```

Or specify projects and versions explicitly:

```bash
sbt "releaseIOMonorepo core with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"
```

For local rehearsal recipes, see [docs/core/recipes.md](docs/core/recipes.md) and
[docs/monorepo/recipes.md](docs/monorepo/recipes.md).

## Build & Test

```bash
sbt compile              # compile both modules
sbt test                 # run unit tests (MUnit)
sbt -Dsbt.version=2.0.0-RC9 compile  # compile on sbt 2 / Scala 3 (version defined as Sbt2Version in build.sbt)
sbt -Dsbt.version=2.0.0-RC9 test     # run unit tests on sbt 2 / Scala 3
sbt scripted             # run all scripted integration tests
sbt core/test            # core unit tests only
sbt monorepo/test        # monorepo unit tests only
sbt scalafmtAll          # format Scala sources
sbt scalafmtSbt          # format .sbt and project/*.scala build files
sbt scalafmtCheckAll     # verify Scala source formatting
sbt scalafmtSbtCheck     # verify sbt/build file formatting
```

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
