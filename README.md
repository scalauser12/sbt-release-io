# sbt-release-io

An sbt plugin that wraps [sbt-release](https://github.com/sbt/sbt-release) with cats-effect IO for better functional programming support and error handling.

## Features

- **IO-based release steps**: All release steps return `IO[ReleaseContext]` for composable, referentially transparent operations
- **Full compatibility**: Native IO implementations for VCS commits, tagging, and version management; delegates to upstream sbt-release 1.4.0 for settings
- **Flexible step composition**: Create custom release steps using cats-effect IO
- **Better error handling**: Graceful failure handling with the IO monad
- **Cross-build support**: Run both checks and actions across multiple Scala versions
- **Upstream-style helper commands**: Run individual release phases with `release-*` commands
- **Optional interactive mode**: Enable upstream-like prompts for versions, confirmation, and push
- **Configurable**: Respects all upstream sbt-release settings (commit messages, signing, version bumping, etc.)

## Installation

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.sbt-release-io" % "sbt-release-io" % "0.1.0-SNAPSHOT")
```

## Usage

### Basic Release

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

### Extra Commands

The plugin also provides upstream-style helper commands for running individual phases:

```bash
sbt release-vcs-checks
sbt release-check-snapshot-dependencies
sbt release-inquire-versions
sbt release-set-release-version
sbt release-set-next-version
sbt release-commit-release-version
sbt release-commit-next-version
sbt release-tag-release
sbt release-push-changes
```

### Configuration

In `build.sbt`:

```scala
import io.release.ReleasePluginIO.autoImport._
import io.release.steps.ReleaseSteps

// Use default release steps (recommended)
releaseIOProcess := ReleaseSteps.defaults

// Or customize the release process
releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  ReleaseSteps.checkCleanWorkingDir,
  ReleaseSteps.checkSnapshotDependencies,
  ReleaseSteps.inquireVersions,
  ReleaseSteps.runClean,
  ReleaseSteps.runTests,
  ReleaseSteps.setReleaseVersion,
  ReleaseSteps.commitReleaseVersion,
  ReleaseSteps.tagRelease,
  ReleaseSteps.publishArtifacts,
  ReleaseSteps.setNextVersion,
  ReleaseSteps.commitNextVersion,
  ReleaseSteps.pushChanges
)

// Enable cross-building by default
releaseIOCrossBuild := true

// Skip publish during release
releaseIOSkipPublish := true

// Enable interactive prompts (disabled by default)
releaseIOInteractive := true
```

### Custom Release Steps

Create custom release steps using the IO-based API:

```scala
import cats.effect.IO
import io.release.{ReleaseContext, ReleaseStepIO}

// Simple custom step
val printBanner = ReleaseStepIO.io("print-banner") { ctx =>
  IO {
    println("=== RELEASE IN PROGRESS ===")
    ctx
  }
}

// Step with VCS operations
val validateBranch = ReleaseStepIO.io("validate-branch") { ctx =>
  ctx.vcs match {
    case Some(vcs) =>
      IO.blocking(vcs.currentBranch).flatMap { branch =>
        if (branch == "main") IO.pure(ctx)
        else IO.raiseError(new RuntimeException(s"Must release from main, not $branch"))
      }
    case None =>
      IO.raiseError(new RuntimeException("VCS not initialized"))
  }
}

// Compose custom steps with built-in ones
releaseIOProcess := Seq(
  printBanner,
  ReleaseSteps.initializeVcs,
  validateBranch,
  ReleaseSteps.checkCleanWorkingDir,
  // ... more steps
)
```

### Custom Plugins

If your release process needs a shared resource — an HTTP client, a database connection, a temporary directory — you can create a custom plugin that extends `ReleasePluginIOLike[T]`. The resource is acquired once before all steps run and released after they complete (or on failure), following the cats-effect `Resource` pattern.

#### Creating the plugin

Custom plugins must be defined in `project/*.scala` (not `build.sbt`) because sbt discovers `AutoPlugin` classes during meta-build compilation.

> **Important:** In `project/*.scala` files, `io.release` is shadowed by sbt's `sbt.io` package. Always use `_root_.io.release` and `_root_.cats.effect` imports.

Here is a plugin that manages an HTTP client for the release process:

```scala
// project/MyReleasePlugin.scala
import sbt._
import sbt.Keys._
import _root_.io.release._
import _root_.cats.effect.{IO, Resource}

object MyReleasePlugin extends ReleasePluginIOLike[HttpClient] {
  // Use noTrigger to coexist with the default ReleasePluginIO
  override def trigger = noTrigger

  // Use a distinct command name to avoid colliding with the default `releaseIO`
  override protected def commandName: String = "releaseWithClient"

  // Acquire the resource before steps run, release it after
  override def resource: Resource[IO, HttpClient] =
    Resource.make(
      IO { val c = new HttpClient("https://api.example.com"); c.connect(); c }
    )(c =>
      IO { c.close() }
    )

  // Steps that use the resource — appended after the settings-based steps
  override protected def additionalSteps: Seq[HttpClient => ReleaseStepIO] = Seq(
    (client: HttpClient) => ReleaseStepIO.io("notify-api") { ctx =>
      IO.blocking {
        client.post("/releases", s"""{"version": "${ctx.versions.map(_._1).getOrElse("")}"}""")
        ctx
      }
    }
  )
}
```

#### Configuring in build.sbt

Enable the plugin and configure the release process as usual:

```scala
// build.sbt
enablePlugins(MyReleasePlugin)

// All standard settings work — they come from the default ReleasePluginIO
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes"
}

releaseIOCrossBuild := true
```

Run the release with your custom command name:

```bash
sbt "releaseWithClient with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

The command accepts the same arguments as `releaseIO` (`with-defaults`, `skip-tests`, `cross`, `release-version`, `next-version`, `default-tag-exists-answer`).

#### Inserting steps at specific positions

`additionalSteps` appends steps to the end of the process. If you need to insert a step at a specific position (e.g., right after VCS initialization), override `releaseProcess` directly:

```scala
override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] = {
  val defaults = super.releaseProcess(state)
  // Insert after initializeVcs (index 0) and checkCleanWorkingDir (index 1)
  val (before, after) = defaults.splitAt(2)
  before ++ Seq((client: HttpClient) => notifyStep(client)) ++ after
}
```

`super.releaseProcess(state)` reads from the `releaseIOProcess` setting (including any `build.sbt` filtering) and appends `additionalSteps`, so your override builds on all configured steps.

#### Key design points

| Concern | Approach |
|---------|----------|
| **Coexisting with default plugin** | Use `trigger = noTrigger` + `enablePlugins(...)` in `build.sbt`, and override `commandName` to avoid duplicate command registration |
| **Adding resource steps** | Override `additionalSteps` (append) or `releaseProcess` (insert at position) |
| **Setting keys** | All `releaseIO*` setting keys are singletons — they work regardless of which plugin exports them |

### Upstream sbt-release Settings

All upstream sbt-release settings are supported:

```scala
import sbtrelease.ReleasePlugin.autoImport._

releaseVersionBump := sbtrelease.Version.Bump.Minor
releaseTagName := s"v${version.value}"
releaseTagComment := s"Releasing ${version.value}"
releaseCommitMessage := s"Setting version to ${version.value}"
releaseNextCommitMessage := s"Setting version to ${version.value}"
releaseIgnoreUntrackedFiles := false
releaseVcsSign := false
releaseVcsSignOff := false
releaseVersionFile := baseDirectory.value / "version.sbt"
releaseUseGlobalVersion := true
releasePublishArtifactsAction := publish.value
```

## Default Release Steps

The default release process includes:

1. **initializeVcs** - Detect and initialize VCS (Git/Mercurial/Subversion)
2. **checkCleanWorkingDir** - Verify no uncommitted changes
3. **checkSnapshotDependencies** - Verify no snapshot dependencies
4. **inquireVersions** - Determine release and next versions
5. **runClean** - Clean project build outputs
6. **runTests** - Run tests (unless `skip-tests`)
7. **setReleaseVersion** - Update version.sbt to release version
8. **commitReleaseVersion** - Commit version change
9. **tagRelease** - Create Git tag
10. **publishArtifacts** - Publish to repository
11. **setNextVersion** - Update version.sbt to next snapshot
12. **commitNextVersion** - Commit version change
13. **pushChanges** - Push commits and tags to remote

## Testing

This plugin includes comprehensive scripted tests.

Run all tests:
```bash
sbt scripted
```

Run specific test:
```bash
sbt "scripted sbt-release-io/simple"
```

See `src/sbt-test/README.md` for test documentation.

## Compatibility

- **sbt**: 1.x
- **Scala**: 2.12
- **sbt-release**: 1.4.0
- **cats-effect**: 3.6.3

## Execution Model: sbt-release-io vs sbt-release

The two plugins use fundamentally different execution models. Each has trade-offs.

### sbt-release (upstream)

sbt-release chains `State => State` functions via `Function.chain`. Each step transforms the sbt state and returns it. The command handler returns immediately, and sbt's event loop processes any enqueued commands (e.g., `+publish` for cross-builds) between steps.

### sbt-release-io

sbt-release-io builds a single `IO[ReleaseContext]` program from all steps, then executes it with `unsafeRunSync()` on the sbt command thread. The entire release — checks, actions, cross-builds, resource lifecycle — completes before control returns to sbt.

### Advantages of the IO model

- **Resource safety**: `Resource.use` guarantees cleanup (close connections, release locks) even on failure or interruption. sbt-release has no equivalent — cleanup requires manual `try/finally` in each step.
- **Composability**: Steps compose with `for`/`flatMap` and standard cats-effect combinators. Custom steps can use `IO.blocking`, `IO.race`, `IO.timeout`, retry logic, etc. sbt-release steps are opaque `State => State` functions with no built-in combinators.
- **Explicit blocking boundaries**: `IO.blocking` marks which operations shell out to git or run sbt tasks. The cats-effect runtime dispatches these to a blocking thread pool, keeping the compute pool free. sbt-release runs everything on the sbt command thread with no distinction.
- **Typed context threading**: `ReleaseContext` carries VCS, versions, and attributes through the step chain with type safety. sbt-release uses untyped `State` attributes (`state.get(key)`) that can fail at runtime if a prior step didn't set the expected attribute.
- **Cross-build checks**: Both checks and actions are cross-built when `enableCrossBuild = true`. sbt-release only cross-builds actions, not checks — a SNAPSHOT dependency present only under a non-default Scala version can slip through.
- **Custom plugins with resources**: `ReleasePluginIOLike[T]` lets you define a plugin parameterized by a resource type (HTTP client, temp directory, etc.) that is acquired once and shared across all steps.

### Trade-offs

- **Blocks the sbt command thread**: `unsafeRunSync()` holds the sbt command thread for the entire release. sbt can't process other commands or respond to UI events until the release finishes. In practice this is rarely a problem — releases are inherently sequential — but it differs from sbt-release's cooperative model.
- **Command enqueuing requires manual draining**: sbt-release lets commands like `+publish` enqueue sub-commands that sbt's event loop processes naturally. sbt-release-io must manually drain enqueued commands inside `fromCommandAndRemaining` — a reimplementation of sbt's event loop within a blocking context.
- **Cats-effect runtime overhead**: The global `IORuntime` creates compute and blocking thread pools that persist for the sbt session, even when no release is running.
- **Signal handling**: While `unsafeRunSync` blocks, sbt's interrupt handling is delayed. `Resource` finalizers still run on JVM shutdown, but the response to Ctrl+C is less immediate than with sbt-release's cooperative model.

### Summary

| Aspect | sbt-release | sbt-release-io |
|--------|-------------|----------------|
| Step type | `State => State` | `ReleaseContext => IO[ReleaseContext]` |
| Execution | Cooperative with sbt event loop | Single blocking `unsafeRunSync` call |
| Resource management | Manual | `Resource.use` with guaranteed cleanup |
| Cross-build checks | Actions only | Both checks and actions |
| Custom plugin resources | Not supported | `ReleasePluginIOLike[T]` |
| Error handling | `FailureCommand` sentinel in State | `IO.raiseError` + `handleErrorWith` |
| Composability | `Function.chain` | Monadic (`for`/`flatMap`) |

The plugin maintains full compatibility with upstream sbt-release settings.

## Contributing

Contributions are welcome! Please ensure:

1. All tests pass (`sbt scripted`)
2. Code compiles (`sbt compile`)
3. No breaking changes to public API
4. Add tests for new features

## License

This project follows the same license as sbt-release.

## Acknowledgments

Built on top of [sbt-release](https://github.com/sbt/sbt-release) by sbt organization.
