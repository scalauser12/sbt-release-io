# sbt-release-io

[![Maven Central](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io_2.12_1.0)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io_2.12_1.0)

A cats-effect IO port of [sbt-release](https://github.com/sbt/sbt-release) for sbt, with composable error handling and resource safety.

## Features

Two-phase release steps (`validate` / `execute`) in cats-effect `IO`, hook-based lifecycle
customization, cross-build support, resource-safe custom plugins (`ReleasePluginIOLike[T]`),
optional interactive prompts, and configurable version files and VCS behavior.

## Quick start

`project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.13.7")
```

Add `version.sbt` with `ThisBuild / version := "0.1.0-SNAPSHOT"`. The plugin loads automatically
on projects that enable `JvmPlugin` (`allRequirements` with a `JvmPlugin` requirement).

For a safe first rehearsal, keep publishing and pushing out of the lifecycle:

```scala
releaseIOPolicyEnablePublish := false
releaseIOPolicyEnablePush    := false
```

Initialize Git if needed, then commit the plugin, version file, and rehearsal settings. The
preflight requires a Git repository with a clean working tree, and the setup commit verifies the
author identity needed by the release commits.

First command:

```bash
sbt "releaseIO check with-defaults"
```

The policy settings above also make a full local rehearsal safe from artifact publication and
remote pushes. Before a production release, remove them and configure `publishTo` (or mark
projects as `publish / skip`), publishing credentials, and a writable Git tracking remote that
permits branch updates and, when tagging is enabled, permits tag updates and supports atomic
multi-ref pushes. Once the push policy is enabled, `with-defaults` supplies the built-in yes answer
only when neither `default-push-answer` nor `releaseIODefaultsPushAnswer` provides an answer.

Customization uses grouped `releaseIOPolicy*` keys, `releaseIOHooks*`, and resource-aware
custom plugins when one shared resource is needed. The older flat key names and lower-level
step DSL were removed in the breaking API cleanup.

## Read next

- [Core getting started](../../docs/core/getting-started.md) for installation, the first `help`,
  `check`, and full-release commands, and the default built-in steps
- [Core customization walkthrough](../../docs/core/customization-walkthrough.md) for a safe local rehearsal using policy keys and lifecycle hooks
- [Core configuration](../../docs/core/configuration.md) for starter `build.sbt` patterns, and [Core reference](../../docs/core/reference.md) for the full settings and CLI catalog
- [Core customization](../../docs/core/customization.md) for hooks, resource-aware custom plugins, and snippet recipes
- [Core operations](../../docs/core/operations.md) for rollback and recovery
- [Docs index](../../docs/README.md) for the full documentation tree

## License

This project is licensed under the [Apache License 2.0](../../LICENSE).
