# sbt-release-io

[![Built with Claude Code](https://img.shields.io/badge/Built%20with-Claude%20Code-blueviolet?logo=anthropic)](https://claude.ai/claude-code)
[![sbt-release-io](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io_2.12_1.0?label=sbt-release-io)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io_2.12_1.0)
[![sbt-release-io-monorepo](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0?label=sbt-release-io-monorepo)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)

A cats-effect IO port of [sbt-release](https://github.com/sbt/sbt-release) for sbt, with composable error handling and resource safety.

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| [core](modules/core/README.md) | `sbt-release-io` | IO-based release plugin for single-project builds. Independent codebase porting sbt-release onto cats-effect IO with `Resource` lifecycle, cross-build validation, and typed context threading. |
| [monorepo](modules/monorepo/README.md) | `sbt-release-io-monorepo` | Monorepo extension with per-project versioning, git-based change detection, topological ordering, per-project failure isolation, and tagging strategies. |

## Quick Start

### Single project

In `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.4.2")
```

```bash
sbt "releaseIO with-defaults"
```

### Monorepo

In `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.4.2")
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
sbt -Dsbt.version=2.0.0-RC9 compile  # compile on sbt 2 / Scala 3
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
- **Scala**: 2.12.21 and 3.8.1
- **cats-effect**: 3.6.3
- **VCS**: Git only

## License

This project is licensed under the [Apache License 2.0](LICENSE).

## Acknowledgments

Ports [sbt-release](https://github.com/sbt/sbt-release) by the sbt organization onto cats-effect IO.

Developed with the assistance of [Claude Code](https://claude.ai/claude-code) by Anthropic.
