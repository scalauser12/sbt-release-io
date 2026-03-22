# Getting started (core)

## Features

- **Two-phase release steps**: Each step has a `validate` phase (preflight checks) and an `execute` phase (actions), both running in cats-effect `IO`
- **Independent codebase**: Ports sbt-release's types, settings, and execution model onto cats-effect IO — no runtime dependency on sbt-release
- **Flexible step composition**: Create custom release steps using cats-effect IO
- **Better error handling**: Graceful failure handling with the IO monad
- **Cross-build support**: Run both validation and execution phases across multiple Scala versions
- **Resource-safe custom plugins**: Acquire shared resources (HTTP clients, temp dirs, etc.) once for the entire release with guaranteed cleanup via `Resource[IO, T]`
- **Optional interactive mode**: Enable sbt-release-compatible prompts for versions, confirmation, and push
- **Configurable**: Comprehensive settings for commit messages, signing, version bumping, etc.

## Installation

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.5.3")
```

The project needs a `version.sbt` file containing `ThisBuild / version := "0.1.0-SNAPSHOT"`. The plugin reads and writes this file during the release. The file path and format can be customized — see [Custom version formats](configuration.md#custom-version-formats).

## Usage

Run the release process:

```bash
sbt releaseIO
```

With command-line options:

```bash
# Use default answers for all prompts
sbt "releaseIO with-defaults"

# Skip tests
sbt "releaseIO skip-tests"

# Enable cross-building
sbt "releaseIO cross"

# Specify versions
sbt "releaseIO release-version 1.0.0 next-version 1.1.0-SNAPSHOT"

# Combine options
sbt "releaseIO with-defaults skip-tests release-version 1.0.0"
```

## Default release steps

The default release process includes:

1. **initialize-vcs** - Detect and initialize VCS (Git)
2. **check-clean-working-dir** - Verify no uncommitted changes
3. **check-snapshot-dependencies** - Verify no snapshot dependencies
4. **inquire-versions** - Determine release and next versions
5. **run-clean** - Clean project build outputs (project-scoped `clean`)
6. **run-tests** - Run tests (unless `skip-tests`)
7. **set-release-version** - Update version.sbt to release version
8. **commit-release-version** - Commit version change
9. **tag-release** - Create Git tag
10. **publish-artifacts** - Publish to repository
11. **set-next-version** - Update version.sbt to next snapshot
12. **commit-next-version** - Commit version change
13. **push-changes** - Push commits and tags to remote

These names are the stable built-in insertion points for `insertAfter` and `insertBefore`.
