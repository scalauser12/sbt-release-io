# sbt-release-io

[![Maven Central](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io_2.12_1.0)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io_2.12_1.0)

A cats-effect IO port of [sbt-release](https://github.com/sbt/sbt-release) for sbt, with composable error handling and resource safety.

## Features

- **IO-based release steps**: `execute` steps return `IO[ReleaseContext]`, and `validate` steps return `IO[Unit]`
- **Independent codebase**: Ports sbt-release's types, settings, and execution model onto cats-effect IO — no runtime dependency on sbt-release
- **Flexible step composition**: Create custom release steps using cats-effect IO
- **Better error handling**: Graceful failure handling with the IO monad
- **Cross-build support**: Run both validation and execution phases across multiple Scala versions
- **Optional interactive mode**: Enable sbt-release-compatible prompts for versions, confirmation, and push
- **Configurable**: Comprehensive settings for commit messages, signing, version bumping, etc.

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

### Configuration

In `build.sbt`:

> The explicit `import io.release.ReleasePluginIO.autoImport.*` is optional — sbt auto-imports these keys from plugins on the classpath.

```scala
import cats.effect.IO
import io.release.ReleasePluginIO.autoImport.*
import io.release.steps.ReleaseSteps

// Use default release steps (recommended)
releaseIOProcess := ReleaseSteps.defaults

// Or fully customize the release process (see "Custom Steps" below)
releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  validateBranch,             // custom step (defined below) — fail fast
  ReleaseSteps.checkCleanWorkingDir,
  ReleaseSteps.checkSnapshotDependencies,
  ReleaseSteps.inquireVersions,
  ReleaseSteps.runClean,
  ReleaseSteps.runTests,
  ReleaseSteps.setReleaseVersion,
  ReleaseSteps.commitReleaseVersion,
  ReleaseSteps.tagRelease,
  printBanner,                // custom step (defined below)
  ReleaseSteps.publishArtifacts,
  ReleaseSteps.setNextVersion,
  ReleaseSteps.commitNextVersion
  // pushChanges omitted — CI pushes on success
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

// Custom version file writer (default produces `ThisBuild / version := "x.y.z"\n`)
releaseIOWriteVersion := { (_, version) =>
  IO.pure(s"$version\n")
}
```

### Custom Version Formats

The default reader and writer assume a `version.sbt` file containing `[ThisBuild /] version := "x.y.z"`. To use a different version file format — for example, in a non-Scala project or a polyglot monorepo — override three settings together:

| Setting | Role |
|---------|------|
| `releaseIOVersionFile` | Path to the version file |
| `releaseIOReadVersion` | `File => IO[String]` — extract the version string from the file |
| `releaseIOWriteVersion` | `(File, String) => IO[String]` — return the **complete file contents** to write to disk |

The writer receives the current file as its first argument, so it can read existing content and replace only the version line while preserving other fields.

#### Example: Java `.properties` file

Given a `version.properties` file:

```
app.name=my-app
app.version=0.1.0-SNAPSHOT
```

Override the settings in `build.sbt`:

```scala
import cats.effect.IO

releaseIOVersionFile := baseDirectory.value / "version.properties"

// Parse app.version=x.y.z from the properties file
releaseIOReadVersion := { (file: File) =>
  IO.blocking(sbt.IO.read(file)).flatMap { contents =>
    val pattern = """app\.version=(.+)""".r
    pattern.findFirstMatchIn(contents) match {
      case Some(m) => IO.pure(m.group(1).trim)
      case None    => IO.raiseError(
        new RuntimeException(s"Could not parse version from ${file.getName}")
      )
    }
  }
}

// Replace only the app.version line, preserve everything else
releaseIOWriteVersion := { (file: File, ver: String) =>
  IO.blocking(sbt.IO.read(file)).map { contents =>
    contents.linesIterator
      .map {
        case line if line.startsWith("app.version=") => s"app.version=$ver"
        case line                                    => line
      }
      .mkString("\n") + "\n"
  }
}
```

The same pattern works for any text-based format:

- **Plain text** — a file containing only the version string; the reader returns `IO.blocking(sbt.IO.read(file).trim)` and the writer returns `IO.pure(s"$ver\n")`
- **JSON** — parse `{"version": "x.y.z"}` with a JSON library and produce updated JSON
- **YAML** — match `version: x.y.z` with a regex or YAML parser

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

// ReleaseContext public API:
//   ctx.state                          — current sbt State
//   ctx.releaseVersion                 — Option[String]
//   ctx.nextVersion                    — Option[String]
//   ctx.vcs                            — Option[Vcs]
//   ctx.withState(s)                   — update sbt State
//   ctx.withVersions(release, next)    — set version pair
//   ctx.withVcs(v)                     — set VCS adapter
//   ctx.withMetadata(key, value)       — store typed metadata (key: AttributeKey[A])
//   ctx.metadata(key)                  — retrieve typed metadata (key: AttributeKey[A])
//   ctx.withoutMetadata(key)           — remove a metadata entry (key: AttributeKey[A])
//   ctx.fail / ctx.failWith(cause)     — mark context as failed

```

### Creating Steps from sbt Tasks and Commands

Wrap existing sbt tasks, input tasks, or commands as release steps using the built-in factory methods:

```scala
import io.release.ReleasePluginIO.autoImport.*

releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  ReleaseSteps.checkCleanWorkingDir,
  ReleaseSteps.inquireVersions,
  // Run a TaskKey as a release step
  stepTask(myCustomTask),
  // Run a TaskKey with cross-building enabled
  stepTask(myCustomTask, enableCrossBuild = true),
  // Run an InputKey with arguments
  stepInputTask(myInputTask, args = "arg1 arg2"),
  // Run a TaskKey aggregated across subprojects
  stepTaskAggregated(test),
  // Run an sbt command string
  stepCommand("publishLocal"),
  // Run a command that enqueues sub-commands (e.g. +publish)
  stepCommandAndRemaining("+publish"),
  ReleaseSteps.pushChanges
)
```

These are also available directly on `ReleaseStepIO` as `fromTask`, `fromInputTask`, `fromTaskAggregated`, `fromCommand`, `fromCommandAndRemaining`, and `pure` (for non-effectful context transformations).

### Custom Plugins

If your release process needs a shared resource — an HTTP client, a database connection, a temporary directory — you can create a custom plugin that extends `ReleasePluginIOLike[T]`. The resource is acquired once before all steps run and released after they complete (or on failure), following the cats-effect `Resource` pattern.

#### Creating the plugin

Custom plugins must be defined in `project/*.scala` (not `build.sbt`) because sbt discovers `AutoPlugin` classes during meta-build compilation.

> **Important:** In `project/*.scala` files, `io.release` is shadowed by sbt's `sbt.io` package. Always use `_root_.io.release` and `_root_.cats.effect` imports.

Here is a plugin that manages an HTTP client for the release process:

```scala
// project/MyReleasePlugin.scala
import sbt.*
import sbt.Keys.*
import _root_.io.release.*
import _root_.io.release.ReleaseIO.*
import _root_.cats.effect.{IO, Resource}

object MyReleasePlugin extends ReleasePluginIOLike[HttpClient] {
  // Use noTrigger to coexist with the default ReleasePluginIO
  override def trigger = noTrigger

  // Use a distinct command name to avoid colliding with the default `releaseIO`
  override protected def commandName: String = "releaseWithClient"

  // Acquire the resource before steps run, release it after
  override def resource: Resource[IO, HttpClient] =
    Resource.make(IO.blocking {
      val c = new HttpClient("https://api.example.com")
      c.connect()
      c
    })(c => IO.blocking(c.close()))

  // Resource-aware step using the built-in factory (see "Resource-aware step factory methods")
  private val notifyApi = resourceStep[HttpClient]("notify-api") { client => ctx =>
    IO.blocking {
      client.post("/releases", s"""{"version": "${ctx.releaseVersion.getOrElse("")}"}""")
      ctx
    }
  }

  // Append the step after the defaults
  override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] =
    liftSteps(Project.extract(state).get(releaseIOProcess)) :+ notifyApi
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

#### Resource-aware step factory methods

The `ReleaseIO` trait (mixed into all `ReleasePluginIOLike` subclasses) provides convenience factory methods for creating resource-aware steps:

```scala
// Simple resource step — execute only, no validation phase
val notifySlack: HttpClient => ReleaseStepIO = resourceStep("notify-slack") { client => ctx =>
  IO.blocking {
    client.post("/webhook", s"Released ${ctx.releaseVersion.getOrElse("")}")
    ctx
  }
}

// Resource step with a validation phase
val verifyToken: HttpClient => ReleaseStepIO =
  resourceStepWithValidation("verify-token")(
    execute = client => ctx =>
      IO.blocking {
        client.post("/release", "...")
        ctx
      }
  )(
    validate = client => ctx =>
      IO.blocking(client.get("/health"))
  )
```

#### Action variants (side-effect-only steps)

When a resource step performs a side effect without modifying the release context,
use the `Action` variants to avoid the `; ctx` / `.as(ctx)` boilerplate:

```scala
// Action variant — execute returns IO[Unit], context is passed through automatically
val notifyApi: HttpClient => ReleaseStepIO =
  resourceStepAction("notify-api") { client => ctx =>
    IO.blocking { client.post("/releases", ctx.releaseVersion.getOrElse("")) }
  }

// Action variant with validation — both execute and validate return IO[Unit]
val verifyAndNotify: HttpClient => ReleaseStepIO =
  resourceStepActionWithValidation("verify-and-notify")(
    execute = client => ctx =>
      IO.blocking { client.post("/release", ctx.releaseVersion.getOrElse("")) }
  )(
    validate = client => ctx =>
      IO.blocking { client.get("/health") }
  )
```

These are equivalent to their non-action counterparts but the execute function does not
need to return the context — it is passed through unchanged.

Use these in `releaseProcess`:

```scala
override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] =
  liftSteps(Project.extract(state).get(releaseIOProcess)) ++ Seq(notifySlack, verifyToken)
```

#### Inserting steps at specific positions

`liftSteps` appends steps to the end of the process. To insert at a specific position, use `insertAfter` or `insertBefore`:

```scala
override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] =
  insertAfter(Project.extract(state).get(releaseIOProcess), "check-clean-working-dir")(
    Seq(notifySlack)
  )
```

`insertAfter` and `insertBefore` match the exact `step.name` strings shown in
the default-step list below, such as `"check-clean-working-dir"` or `"publish-artifacts"`.

These methods read from the `releaseIOProcess` setting (including any `build.sbt` filtering), so your override builds on all configured steps.

Custom steps inserted before a built-in execute step may update session settings in `State`, and
the later built-in execute step will read those live settings when it runs. This applies to built-in
steps such as version resolution and tagging. It does not change the two-phase model: built-in
`validate` functions still run from the initial validation-phase state.

#### Fully custom release process

Override `releaseProcess` directly to build the step sequence from scratch instead of
appending to the defaults. Plain steps (from `ReleaseSteps`) and resource-aware steps
can be mixed freely — an implicit conversion lifts plain steps into resource-ignoring functions.

```scala
// project/MyReleasePlugin.scala
import sbt.*
import sbt.Keys.*
import _root_.io.release.*
import _root_.io.release.steps.ReleaseSteps
import _root_.cats.effect.{IO, Resource}

object MyReleasePlugin extends ReleasePluginIOLike[HttpClient] {
  override def trigger               = noTrigger
  override protected def commandName = "myRelease"
  override def resource: Resource[IO, HttpClient] =
    Resource.make(IO.blocking(new HttpClient()))(c => IO.blocking(c.close()))

  // --- custom steps extracted for readability ---

  private def validateBranch(client: HttpClient): ReleaseStepIO =
    ReleaseStepIO.io("validate-branch") { ctx =>
      IO.blocking { client.get("/allowed-branches") }.flatMap { branches =>
        if (branches.contains("main")) IO.pure(ctx)
        else IO.raiseError(new RuntimeException("Release blocked"))
      }
    }

  private def notifySlack(client: HttpClient): ReleaseStepIO =
    ReleaseStepIO.io("notify-slack") { ctx =>
      IO.blocking {
        client.post("/webhook", s"Tagged ${ctx.releaseVersion.getOrElse("")}")
        ctx
      }
    }

  private def verifyPublish(client: HttpClient): ReleaseStepIO =
    ReleaseStepIO.io("verify-publish") { ctx =>
      IO.blocking {
        client.get(s"/artifacts/${ctx.releaseVersion.getOrElse("")}")
        ctx
      }
    }

  // --- release pipeline ---

  override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] =
    Seq(
      // Plain steps — lifted automatically via the implicit conversion
      ReleaseSteps.initializeVcs,
      validateBranch,                   // fail fast before any mutations
      ReleaseSteps.checkCleanWorkingDir,
      ReleaseSteps.checkSnapshotDependencies,
      ReleaseSteps.inquireVersions,
      ReleaseSteps.runClean,
      ReleaseSteps.runTests,
      ReleaseSteps.setReleaseVersion,
      ReleaseSteps.commitReleaseVersion,
      ReleaseSteps.tagRelease,
      notifySlack,                      // announce after tagging
      ReleaseSteps.publishArtifacts,
      verifyPublish,                    // confirm artifact is available
      ReleaseSteps.setNextVersion,
      ReleaseSteps.commitNextVersion
      // pushChanges omitted — CI pushes on success
    )
}
```

This bypasses the `releaseIOProcess` setting entirely — the step list is hard-coded
in the plugin. Use `liftSteps`, `insertAfter`, or `insertBefore` (shown above)
if you want to keep the setting-based defaults and only add extra steps.

#### Key design points

| Concern | Approach |
|---------|----------|
| **Coexisting with default plugin** | Use `trigger = noTrigger` + `enablePlugins(...)` in `build.sbt`, and override `commandName` to avoid duplicate command registration |
| **Adding resource steps** | Override `releaseProcess` using `liftSteps` (append), `insertAfter`/`insertBefore` (positional insert) |
| **Setting keys** | All `releaseIO*` setting keys are singletons — they work regardless of which plugin exports them |
| **Do not add autoImport** | Do not define `object autoImport` in custom plugins — it causes ambiguous references with `ReleasePluginIO` (e.g. `reference to releaseIOProcess is ambiguous`) |

### Using Typelevel Libraries in Release Steps

Since release steps run in `IO`, you can use any library from the Typelevel / FP ecosystem in your custom steps. This is useful when your release process needs to do more than run sbt tasks and git commands — for example, uploading archives to a file repository, calling REST APIs, or streaming data.

**Constraint:** sbt 1 plugins run on Scala 2.12 and sbt 2 plugins run on Scala 3, so you must use library versions published for the Scala version that matches your sbt version.

Some libraries that work well in release steps:

| Library | Use case | sbt 1 (Scala 2.12) | sbt 2 (Scala 3) |
|---------|----------|--------------------|-----------------|
| `http4s-ember-client` | HTTP requests (upload artifacts, notify services) | 0.23.x (1.x dropped 2.12) | 0.23.x or 1.x |
| `fs2-io` | Streaming file I/O, process execution | 3.x | 3.x |
| `circe` | JSON encoding/decoding for API calls | 0.14.x | 0.14.x |
| `doobie` | JDBC database access (record release metadata) | 1.x | 1.x |
| `sttp` | Lightweight HTTP client with cats-effect backend | 3.x | 3.x or 4.x |

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
import io.release.ReleaseStepIO
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
          response.as[String].map { errorBody =>
            new RuntimeException(s"Artifact upload failed (${response.status}): $errorBody")
          }
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

### Settings Reference

All release settings use the `releaseIO` prefix:

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `releaseIOProcess` | `SettingKey[Seq[ReleaseStepIO]]` | `ReleaseSteps.defaults` | Ordered sequence of release steps |
| `releaseIOCrossBuild` | `SettingKey[Boolean]` | `false` | Cross-build steps per `crossScalaVersions` |
| `releaseIOSkipPublish` | `SettingKey[Boolean]` | `false` | Skip the publish step entirely |
| `releaseIOInteractive` | `SettingKey[Boolean]` | `false` | Enable interactive prompts |
| `releaseIOVersionFile` | `SettingKey[File]` | `baseDirectory / "version.sbt"` | Path to the version file |
| `releaseIOUseGlobalVersion` | `SettingKey[Boolean]` | `true` | Use `ThisBuild / version` format |
| `releaseIOReadVersion` | `SettingKey[File => IO[String]]` | parses `version := "x.y.z"` | Read version from file |
| `releaseIOWriteVersion` | `SettingKey[(File, String) => IO[String]]` | writes `ThisBuild / version := "x.y.z"` | Produce version file contents |
| `releaseIOVersionBump` | `TaskKey[Version.Bump]` | `Next` | Version bump strategy (see bump types below) |
| `releaseIOVersion` | `TaskKey[String => String]` | strips qualifier/snapshot | Compute release version from current |
| `releaseIONextVersion` | `TaskKey[String => String]` | bumps + appends `-SNAPSHOT` | Compute next dev version |
| `releaseIOTagName` | `TaskKey[String]` | `s"v${version.value}"` | Git tag name |
| `releaseIOTagComment` | `TaskKey[String]` | `s"Releasing ${version.value}"` | Git tag comment |
| `releaseIOCommitMessage` | `TaskKey[String]` | `s"Setting version to ${version.value}"` | Release version commit message |
| `releaseIONextCommitMessage` | `TaskKey[String]` | `s"Setting version to ${version.value}"` | Next version commit message |
| `releaseIOVcsSign` | `SettingKey[Boolean]` | `false` | GPG-sign tags and commits |
| `releaseIOVcsSignOff` | `SettingKey[Boolean]` | `false` | Add `Signed-off-by` to commits |
| `releaseIOIgnoreUntrackedFiles` | `SettingKey[Boolean]` | `false` | Ignore untracked files in clean check |
| `releaseIOPublishArtifactsAction` | `TaskKey[Unit]` | `publish` | Task that performs the publish |
| `releaseIOPublishArtifactsChecks` | `SettingKey[Boolean]` | `true` | Validate `publishTo`/`skip` before publish |
| `releaseIOSnapshotDependencies` | `TaskKey[Seq[ModuleID]]` | auto-resolved | SNAPSHOT deps for validation |
| `releaseIORuntimeVersion` | `TaskKey[String]` | scope-aware `version` | Reads `ThisBuild / version` or `version` based on `releaseIOUseGlobalVersion` |

#### Version Bump Types

| Bump | Example | Description |
|------|---------|-------------|
| `Major` | 1.0.0 → 2.0.0 | Bump major version |
| `Minor` | 1.0.0 → 1.1.0 | Bump minor version |
| `Bugfix` | 1.0.0 → 1.0.1 | Bump bugfix/patch version |
| `Nano` | 1.0.0.0 → 1.0.0.1 | Bump nano version |
| `Next` | 1.0-RC1 → 1.0-RC2 | Increment next component including prerelease **(default)** |
| `NextStable` | 1.0-RC1 → 1.0 | Increment next component, remove prerelease qualifier |

## Default Release Steps

The default release process includes:

1. **initialize-vcs** - Detect and initialize VCS (Git)
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

These names are the stable built-in insertion points for `insertAfter` and `insertBefore`.
Command-line flags and other run invariants are captured before execution starts, but built-in
execute steps resolve operational settings such as version-file handling and tagging from the current
`State` when they run. Custom steps now use the public `validate`/`execute` model directly.

## Migrating Custom Steps

If you are updating a custom plugin or build from an older release:

- rename `step.check` to `step.validate`
- rename `step.action` to `step.execute`
- rename `resourceStepWithCheck` to `resourceStepWithValidation`
- replace string attributes with typed metadata via `ctx.withMetadata`, `ctx.metadata`, and `AttributeKey[A]`

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
- **cats-effect**: 3.6.3

## Execution Model: sbt-release-io vs sbt-release

Both plugins share the same high-level structure: block the sbt command thread, run validation checks before actions, execute steps sequentially, and manually drain enqueued commands (e.g., from `+publish`). The key difference is the effect system — sbt-release uses plain `State => State` functions composed with `Function.chain`, while sbt-release-io wraps everything in cats-effect `IO` and runs it with `unsafeRunSync()`.

### Shared structure

- **Synchronous blocking**: Both plugins hold the sbt command thread for the entire release. sbt-release composes all steps via `Function.chain`; sbt-release-io runs a single `unsafeRunSync()`. Neither returns control to sbt's event loop between steps.
- **Validation then execution**: sbt-release has `check`/`action` fields on `ReleaseStep`; sbt-release-io has `validate`/`execute` on `ReleaseStepIO`. Both run all checks before any actions.
- **Manual command draining**: Both plugins use `@tailrec` loops to drain enqueued commands — sbt-release in `releaseStepCommandAndRemaining`, sbt-release-io in `fromCommandAndRemaining`.

### What IO adds

- **Resource safety**: `Resource.use` guarantees cleanup (close connections, release locks) even on failure or interruption. sbt-release has no equivalent — cleanup requires manual `try/finally` in each step.
- **Composability**: Steps compose with `for`/`flatMap` and standard cats-effect combinators. Custom steps can use `IO.blocking`, `IO.race`, `IO.timeout`, retry logic, etc. sbt-release steps are opaque `State => State` functions with no built-in combinators.
- **Typed validation boundary**: The IO model's validation phase returns `IO[Unit]` (side-effect-aware but context-non-threading), making it explicit that checks cannot alter the release context. sbt-release's `check` field is `State => State`, so checks could theoretically mutate state.
- **Explicit blocking boundaries**: `IO.blocking` marks which operations shell out to git or run sbt tasks. The cats-effect runtime dispatches these to a blocking thread pool, keeping the compute pool free. sbt-release runs everything on the sbt command thread with no distinction.
- **Typed context threading**: `ReleaseContext` carries VCS, versions, and typed metadata through the step chain with type safety. sbt-release uses untyped `State` attributes (`state.get(key)`) that can fail at runtime if a prior step didn't set the expected attribute.
- **Cross-build validation**: Both `validate` and `execute` phases are cross-built when `enableCrossBuild = true`. sbt-release only cross-builds actions, not preflight validation — a SNAPSHOT dependency present only under a non-default Scala version can slip through.
- **Custom plugins with resources**: `ReleasePluginIOLike[T]` lets you define a plugin parameterized by a resource type (HTTP client, temp directory, etc.) that is acquired once and shared across all steps.

### IO-specific costs

- **Cats-effect runtime overhead**: The global `IORuntime` creates compute and blocking thread pools that persist for the sbt session, even when no release is running.
- **Signal handling**: While `unsafeRunSync` blocks, sbt's interrupt handling is delayed. On graceful shutdown, cats-effect's runtime attempts to cancel running fibers and run `Resource` finalizers, but this is best-effort. sbt-release, blocking synchronously via `Function.chain`, responds to Ctrl+C more directly.

### Summary

| Aspect | sbt-release | sbt-release-io |
|--------|-------------|----------------|
| Effect system | Plain `State => State` via `Function.chain` | `IO`-wrapped via `unsafeRunSync` |
| Step type | `ReleaseStep(action, check)` | `ReleaseStepIO(validate, execute)` |
| Resource management | Manual | `Resource.use` with guaranteed cleanup |
| Cross-build validation | Actions only | Both `validate` and `execute` phases |
| Custom plugin resources | Not supported | `ReleasePluginIOLike[T]` |
| Error handling | `FailureCommand` sentinel in State | `IO.raiseError` + `handleErrorWith` |
| Composability | `Function.chain` | Monadic (`for`/`flatMap`) |

The plugin ports sbt-release's types, settings, and execution model onto cats-effect IO, with no runtime dependency on sbt-release.

## Contributing

Contributions are welcome! Please ensure:

1. All tests pass (`sbt scripted`)
2. Code compiles (`sbt compile`)
3. No breaking changes to public API
4. Add tests for new features

## License

Apache License 2.0

## Acknowledgments

Ports [sbt-release](https://github.com/sbt/sbt-release) by the sbt organization onto cats-effect IO.
