# sbt-release-io

[![Maven Central](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io_2.12_1.0)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io_2.12_1.0)

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
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.4.2")
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

> The explicit `import io.release.ReleasePluginIO.autoImport._` is optional — sbt auto-imports these keys from plugins on the classpath.

```scala
import io.release.ReleasePluginIO.autoImport._
import io.release.steps.ReleaseSteps

// Use default release steps (recommended)
releaseIOProcess := ReleaseSteps.defaults

// Or customize the release process
releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  ReleaseSteps.checkCleanWorkingDir,
  ReleaseSteps.inquireVersions,
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

// Custom version file reader (default parses `[ThisBuild /] version := "x.y.z"`)
releaseIOReadVersion := { file =>
  IO.blocking(sbt.IO.read(file).trim)
}

// Custom version file writer (default produces `version := "x.y.z"\n`)
releaseIOWriteVersion := { (_, version) =>
  IO.pure(s"$version\n")
}
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

  // Append a resource-aware step after the defaults
  override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] =
    defaultsWith(state)(
      (client: HttpClient) => ReleaseStepIO.io("notify-api") { ctx =>
        IO.blocking {
          client.post("/releases", s"""{"version": "${ctx.releaseVersion.getOrElse("")}"}""")
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

`defaultsWith` appends steps to the end of the process. To insert at a specific position, use `defaultsWithAfter` or `defaultsWithBefore`:

```scala
override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] =
  defaultsWithAfter(state, "check-clean-working-dir")(
    (client: HttpClient) => notifyStep(client)
  )
```

`defaultsWithAfter` and `defaultsWithBefore` match the exact `step.name` strings shown in
the default-step list below, such as `"check-clean-working-dir"` or `"publish-artifacts"`.

These helpers read from the `releaseIOProcess` setting (including any `build.sbt` filtering), so your override builds on all configured steps.

Custom steps inserted before a built-in action may update session settings in `State`, and the
later built-in action will read those live settings when it runs. This applies to built-in actions
such as version resolution and tagging. It does not change the two-phase model: built-in `check`
functions still run from the initial check-phase state.

> **Note:** Each helper inserts at a single position. To insert custom steps at multiple
> non-adjacent positions, use the fully custom approach below.

#### Fully custom release process

Override `releaseProcess` directly to build the step sequence from scratch instead of
appending to the defaults. Plain steps (from `ReleaseSteps`) and resource-aware steps
can be mixed freely — an implicit conversion lifts plain steps into resource-ignoring functions.

```scala
// project/MyReleasePlugin.scala
import sbt._
import sbt.Keys._
import _root_.io.release._
import _root_.io.release.steps.ReleaseSteps
import _root_.cats.effect.{IO, Resource}

object MyReleasePlugin extends ReleasePluginIOLike[HttpClient] {
  override def trigger                    = noTrigger
  override protected def commandName      = "myRelease"
  override def resource: Resource[IO, HttpClient] =
    Resource.make(IO(new HttpClient()))(c => IO(c.close()))

  override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] =
    Seq(
      // Plain steps — lifted automatically via the implicit conversion
      ReleaseSteps.initializeVcs,
      ReleaseSteps.checkCleanWorkingDir,
      // 1st custom step — validate branch before anything else runs
      (client: HttpClient) => ReleaseStepIO.io("validate-branch") { ctx =>
        IO.blocking { client.get("/allowed-branches") }.flatMap { branches =>
          if (branches.contains("main")) IO.pure(ctx)
          else IO.raiseError(new RuntimeException("Release blocked"))
        }
      },
      ReleaseSteps.checkSnapshotDependencies,
      ReleaseSteps.inquireVersions,
      ReleaseSteps.runClean,
      ReleaseSteps.runTests,
      ReleaseSteps.setReleaseVersion,
      ReleaseSteps.commitReleaseVersion,
      ReleaseSteps.tagRelease,
      // 2nd custom step — notify after tagging
      (client: HttpClient) => ReleaseStepIO.io("notify-slack") { ctx =>
        IO.blocking { client.post("/webhook", s"Tagged!"); ctx }
      },
      ReleaseSteps.publishArtifacts,
      // 3rd custom step — verify the published artifact
      (client: HttpClient) => ReleaseStepIO.io("verify-publish") { ctx =>
        IO.blocking { client.get(s"/artifacts/${ctx.releaseVersion.getOrElse("")}"); ctx }
      },
      ReleaseSteps.setNextVersion,
      ReleaseSteps.commitNextVersion,
      ReleaseSteps.pushChanges
    )
}
```

This bypasses the `releaseIOProcess` setting entirely — the step list is hard-coded
in the plugin. Use `defaultsWith`, `defaultsWithAfter`, or `defaultsWithBefore` (shown above)
if you want to keep the setting-based defaults and only add extra steps.

#### Key design points

| Concern | Approach |
|---------|----------|
| **Coexisting with default plugin** | Use `trigger = noTrigger` + `enablePlugins(...)` in `build.sbt`, and override `commandName` to avoid duplicate command registration |
| **Adding resource steps** | Override `releaseProcess` using `defaultsWith` (append), `defaultsWithAfter`/`defaultsWithBefore` (positional insert) |
| **Setting keys** | All `releaseIO*` setting keys are singletons — they work regardless of which plugin exports them |
| **Do not add autoImport** | Do not define `object autoImport` in custom plugins — it causes ambiguous references with `ReleasePluginIO` (e.g. `reference to releaseIOProcess is ambiguous`) |

### Using Typelevel Libraries in Release Steps

Since release steps run in `IO`, you can use any library from the Typelevel / FP ecosystem in your custom steps. This is useful when your release process needs to do more than run sbt tasks and git commands — for example, uploading archives to a file repository, calling REST APIs, or streaming data.

**Constraint:** sbt plugins run on Scala 2.12, so you must use library versions published for 2.12.

Some libraries that work well in release steps:

| Library | Use case | Version constraint |
|---------|----------|--------------------|
| `http4s-ember-client` | HTTP requests (upload artifacts, notify services) | 0.23.x only (1.x dropped 2.12) |
| `fs2-io` | Streaming file I/O, process execution | 3.x |
| `circe` | JSON encoding/decoding for API calls | 0.14.x |
| `doobie` | JDBC database access (record release metadata) | 1.x |
| `sttp` | Lightweight HTTP client with cats-effect backend | 3.x |

Add the dependency in `project/plugins.sbt` alongside the plugin:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.4.2")
libraryDependencies += "org.http4s" %% "http4s-ember-client" % "0.23.30"
```

Example: compressing an already-built release archive and uploading it to an internal artifact
service after `publishArtifacts`:

```scala
import cats.effect.IO
import fs2.compression.Compression
import fs2.io.file.{Files, Path}
import io.release.{ReleaseContext, ReleaseStepIO}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.{Method, Request, Uri}

val uploadArchive: ReleaseStepIO = ReleaseStepIO.io("upload-archive") { ctx =>
  ctx.releaseVersion match {
    case Some(version) =>
      val archivePath = Path(s"target/myproject-$version.tar")
      val uploadUri   =
        Uri.unsafeFromString(
          s"https://artifacts.example.com/releases/myproject-$version.tar.gz"
        )
      val body        = Files[IO].readAll(archivePath).through(Compression[IO].gzip())
      val request     = Request[IO](Method.PUT, uploadUri).withBodyStream(body)

      EmberClientBuilder.default[IO].build.use { client =>
        client.expectOr[Unit](request) { response =>
          IO.pure(new RuntimeException(s"Artifact upload failed: ${response.status}"))
        }
      }.as(ctx)

    case None =>
      IO.raiseError(new RuntimeException("releaseVersion is not set"))
  }
}
```

Place this step after `publishArtifacts` so it runs only after the standard repository publish succeeds:

```scala
import io.release.steps.ReleaseSteps

releaseIOProcess := ReleaseSteps.defaults.flatMap {
  case step if step.name == "publish-artifacts" => Seq(step, uploadArchive)
  case step                                     => Seq(step)
}
```

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

1. **initialize-vcs** - Detect and initialize VCS (Git/Mercurial/Subversion)
2. **check-clean-working-dir** - Verify no uncommitted changes
3. **check-snapshot-dependencies** - Verify no snapshot dependencies
4. **inquire-versions** - Determine release and next versions
5. **run-clean** - Clean project build outputs (`clean` on sbt 1, build-wide `cleanFull` on sbt 2)
6. **run-tests** - Run tests (unless `skip-tests`)
7. **set-release-version** - Update version.sbt to release version
8. **commit-release-version** - Commit version change
9. **tag-release** - Create Git tag
10. **publish-artifacts** - Publish to repository
11. **set-next-version** - Update version.sbt to next snapshot
12. **commit-next-version** - Commit version change
13. **push-changes** - Push commits and tags to remote

These names are the stable built-in insertion points for `defaultsWithAfter` and `defaultsWithBefore`.
Command-line flags and other run invariants are captured before execution starts, but built-in
actions resolve operational settings such as version-file handling and tagging from the current
`State` when they run. The public check/action step model remains unchanged for compatibility.

## Testing

This plugin includes comprehensive scripted tests.

Run all tests:
```bash
sbt scripted
```

Run specific test:
```bash
sbt "core/scripted sbt-release-io/simple"
```

See `src/sbt-test/README.md` for test documentation.

## Compatibility

- **sbt**: 1.12.3 and 2.0.0-RC9
- **Scala**: 2.12.21 and 3.8.1
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
