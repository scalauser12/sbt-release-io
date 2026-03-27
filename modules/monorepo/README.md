# sbt-release-io-monorepo

[![Maven Central](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)

A monorepo release plugin for sbt, extending [sbt-release-io](../../docs/core/README.md) with per-project version files, git-based change detection, topological ordering, per-project failure isolation, and per-project tags.

## Features

Per-project steps, hook-based lifecycle customization, change detection,
validate-then-execute phases, per-project tags, cross-build, and
`MonorepoReleasePluginLike[T]` for shared resources.

## Quick start

`project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.6.0")
```

`build.sbt` (root):

```scala
lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
```

Each subproject needs `version.sbt` with `version := "0.1.0-SNAPSHOT"`.

Inspect the command help first:

```bash
sbt "releaseIOMonorepo help"
```

Run a preflight with no release side effects:

```bash
sbt "releaseIOMonorepo check with-defaults"
```

Run the actual release:

```bash
sbt "releaseIOMonorepo with-defaults"
```

Preferred customization keeps the built-in process intact and uses
`releaseIOMonorepoEnable*` policies plus `releaseIOMonorepo*Hooks`. Raw
`releaseIOMonorepoProcess` editing remains available as a legacy advanced path.

## Documentation

Full guide: **[Documentation](../../docs/monorepo/README.md)** (usage, walkthrough, configuration, change detection, customization).

Core plugin (shared settings): [docs/core/README.md](../../docs/core/README.md). Index: [docs/README.md](../../docs/README.md).

Demo repos: [scala-monorepo-demo](https://github.com/scalauser12/scala-monorepo-demo), [files-monorepo-demo](https://github.com/scalauser12/files-monorepo-demo).

## License

This project is licensed under the [Apache License 2.0](../../LICENSE).
