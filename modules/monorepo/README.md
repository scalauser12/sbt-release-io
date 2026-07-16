# sbt-release-io-monorepo

[![Maven Central](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)

A monorepo release plugin for sbt, built on top of
[sbt-release-io](../../docs/core/README.md), with per-project version files, git-based change
detection, topological ordering, per-project failure isolation, and per-project tags.

## Features

Per-project steps, hook-based lifecycle customization, change detection,
validate-then-execute phases, per-project tags, cross-build, and
`MonorepoReleasePluginLike[T]` for shared resources.

## Quick start

`project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.13.6")
```

> **Note:** This README describes the current published monorepo contract in `v0.13.6`; see
> [CHANGELOG.md](../../CHANGELOG.md) for the full release history.

`build.sbt` (root):

```scala
lazy val core = (project in file("core"))
lazy val api  = (project in file("api"))

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    // Keep the first release local while you verify the setup.
    releaseIOMonorepoPolicyEnablePush    := false,
    releaseIOMonorepoPolicyEnablePublish := false
  )
```

Each subproject needs `version.sbt` with `version := "0.1.0-SNAPSHOT"`. Commit the build
and version files before running the clean-working-tree preflight. That setup commit also verifies
the author identity needed by the release commits.

First preflight:

```bash
sbt "releaseIOMonorepo check with-defaults"
```

The starter settings keep both the preflight and the first full release local. Remove them
only after each publishing project has a valid `publishTo`/credentials setup and the current
branch has the upstream remote that `push-changes` should use. The remote must permit branch
updates and, when tagging is enabled, permit tag updates and support atomic multi-ref pushes. Once
push is enabled, `with-defaults` supplies the built-in yes answer only when neither
`default-push-answer` nor `releaseIODefaultsPushAnswer` provides an answer.

Monorepo installs also expose the shared/core `releaseIO*` settings surface transitively.
Use `MonorepoReleasePlugin.autoImport` for `releaseIOMonorepo*` keys and
`ReleasePluginIO.autoImport` for shared/core grouped keys in Scala build sources.
Use `releaseIOMonorepo` as the release command in the documented monorepo setup.

Customization uses grouped `releaseIOMonorepoPolicy*` keys, `releaseIOMonorepoHooks*`, and
resource-aware custom plugins. The older flat key names and lower-level step DSL were removed
in the breaking API cleanup. Raw process override is no longer part of the supported public
surface.

## Read next

- [Monorepo getting started](../../docs/monorepo/getting-started.md) for installation, the
  first `help` and `check` commands, a full release, and the main navigation path
- [First release walkthrough](../../docs/monorepo/walkthrough.md) for an end-to-end setup from scratch
- [Selective release walkthrough](../../docs/monorepo/selective-release-walkthrough.md) for change detection, downstream inclusion, and explicit selectors
- [Monorepo configuration](../../docs/monorepo/configuration.md) for the grouped settings surface and [Monorepo usage](../../docs/monorepo/usage.md) for CLI syntax
- [Monorepo customization](../../docs/monorepo/customization.md) for hooks, resource-aware custom plugins, and snippet recipes
- [Monorepo operations](../../docs/monorepo/operations.md) for rollback and recovery
- [Docs index](../../docs/README.md) and [core plugin docs](../../docs/core/README.md) for shared concepts and settings

Demo repos: [scala-monorepo-demo](https://github.com/scalauser12/scala-monorepo-demo), [files-monorepo-demo](https://github.com/scalauser12/files-monorepo-demo).

## License

This project is licensed under the [Apache License 2.0](../../LICENSE).
