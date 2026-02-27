# sbt-release-io-monorepo

A monorepo release plugin for sbt, extending [sbt-release-io](../core/README.md) with per-project versioning, change detection, topological ordering, and failure isolation.

## Features

- **Per-project release steps**: Steps run once per subproject in topological (dependency) order
- **Change detection**: Git-based detection of which projects changed since the last release tag, with pluggable custom detectors
- **Per-project failure isolation**: A failing project is marked failed without aborting the current step's remaining projects; subsequent steps are skipped entirely
- **Two-phase execution**: All checks run before any actions, so failures are caught before version files or tags are modified
- **Tagging strategies**: Per-project tags (`core/v1.0.0`) or unified tags (`v1.0.0`)
- **Global version mode**: Optional single `version.sbt` at the root, with consistency validation across all projects
- **Cross-build support**: Steps like test and publish run once per `crossScalaVersions` entry
- **Resource-safe custom plugins**: `MonorepoReleasePluginLike[T]` acquires a shared resource (HTTP client, temp dir, etc.) once for the entire release with guaranteed cleanup

## Installation

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.2.0")
```

Enable on your root project in `build.sbt`:

```scala
lazy val core = (project in file("core"))
  .settings(version := "0.2.0")

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(version := "0.2.0")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
```

## Usage

### Command

```
sbt releaseIOMonorepo [project...] [flags] [version overrides]
```

### Flags

| Flag | Description |
|------|-------------|
| `with-defaults` | Skip all interactive prompts, use computed versions |
| `skip-tests` | Skip the `run-tests` step |
| `cross` | Enable cross-build for steps with `enableCrossBuild = true` |
| `all-changed` | Override change detection: release all projects |

### Version overrides

Pin specific release or next versions per project:

```
release-version <project>=<version>
next-version <project>=<version>
```

### Examples

```bash
# Release all changed projects with default versions
sbt "releaseIOMonorepo with-defaults"

# Release only core and api
sbt "releaseIOMonorepo core api with-defaults"

# Pin versions
sbt "releaseIOMonorepo with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"

# Release all projects regardless of changes
sbt "releaseIOMonorepo all-changed with-defaults"

# Enable cross-building
sbt "releaseIOMonorepo cross with-defaults"

# Skip tests
sbt "releaseIOMonorepo skip-tests with-defaults"
```

## Default Release Steps

| # | Step | Type | Description |
|---|------|------|-------------|
| 1 | `initialize-vcs` | Global | Detect git, store VCS adapter in context |
| 2 | `check-clean-working-dir` | Global | Fail if uncommitted changes exist (check phase only) |
| 3 | `resolve-release-order` | Global | Topologically sort projects by dependencies |
| 4 | `detect-or-select-projects` | Global | Run change detection or use explicit CLI selection |
| 5 | `check-snapshot-dependencies` | PerProject | Fail if any SNAPSHOT dependencies found (check phase only, cross-build) |
| 6 | `inquire-versions` | PerProject | Read current version, compute or prompt for release + next |
| 7 | `validate-version-consistency` | Global | In global version mode: verify all projects agree |
| 8 | `run-clean` | PerProject | Run `clean` task |
| 9 | `run-tests` | PerProject | Run `test` task (cross-build enabled, skippable) |
| 10 | `set-release-version` | PerProject | Write release version to `version.sbt` |
| 11 | `commit-release-versions` | Global | Single commit staging all version files |
| 12 | `tag-releases` | Global | Create per-project or unified tags |
| 13 | `publish-artifacts` | PerProject | Publish artifacts (cross-build enabled, skippable) |
| 14 | `set-next-version` | PerProject | Write next snapshot version to `version.sbt` |
| 15 | `commit-next-versions` | Global | Single commit staging all version files |
| 16 | `push-changes` | Global | Push branch + tags to tracking remote |

**Global** steps run once. **PerProject** steps run once per selected project in topological order.

## Configuration

### Core settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoProjects` | `Seq[ProjectRef]` | `thisProject.value.aggregate` | Which subprojects participate in releases |
| `releaseIOMonorepoProcess` | `Seq[MonorepoStepIO]` | `MonorepoReleaseSteps.defaults` | Ordered release steps |
| `releaseIOMonorepoCrossBuild` | `Boolean` | `false` | Enable cross-building by default |
| `releaseIOMonorepoSkipTests` | `Boolean` | `false` | Skip tests |
| `releaseIOMonorepoSkipPublish` | `Boolean` | `false` | Skip publish |
| `releaseIOMonorepoInteractive` | `Boolean` | `false` | Enable interactive version prompts |

### Version settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoVersionFile` | `ProjectRef => File` | `<projectBase>/version.sbt` | Per-project version file resolver |
| `releaseIOMonorepoReadVersion` | `File => IO[String]` | Regex parser (same as core) | Version file reader |
| `releaseIOMonorepoWriteVersion` | `(File, String) => IO[String]` | `version := "x.y.z"\n` | Version file writer (default ignores `File` param) |
| `releaseIOMonorepoUseGlobalVersion` | `Boolean` | `false` | Use root `version.sbt` instead of per-project files |

### Tagging settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoTagStrategy` | `MonorepoTagStrategy` | `PerProject` | `PerProject` or `Unified` |
| `releaseIOMonorepoTagName` | `(String, String) => String` | `(name, ver) => s"$name/v$ver"` | Per-project tag formatter |
| `releaseIOMonorepoUnifiedTagName` | `String => String` | `ver => s"v$ver"` | Unified tag formatter |

### Change detection settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoDetectChanges` | `Boolean` | `true` | Enable git-based change detection |
| `releaseIOMonorepoChangeDetector` | `Option[(ProjectRef, File, State) => IO[Boolean]]` | `None` | Custom change detector |
| `releaseIOMonorepoDetectChangesExcludes` | `Seq[File]` | `Seq.empty` | Files to exclude from detection |

### Example configuration

```scala
import io.release.monorepo.MonorepoReleasePlugin.autoImport._

releaseIOMonorepoSkipTests   := true
releaseIOMonorepoCrossBuild  := true
releaseIOMonorepoTagStrategy := MonorepoTagStrategy.Unified
releaseIOMonorepoTagName     := { (name, ver) => s"release/$name/$ver" }
```

## Custom Steps

### Factory methods

```scala
import cats.effect.IO
import io.release.monorepo.MonorepoReleasePlugin.autoImport._

// Global step — runs once
val validateBranch = globalStep("validate-branch") { ctx =>
  ctx.vcs match {
    case Some(vcs) =>
      IO.blocking(vcs.currentBranch).flatMap { branch =>
        if (branch == "main") IO.pure(ctx)
        else IO.raiseError(new RuntimeException(s"Must release from main, not $branch"))
      }
    case None => IO.raiseError(new RuntimeException("VCS not initialized"))
  }
}

// Per-project step — runs once per selected project
val logProject = perProjectStep("log-project") { (ctx, project) =>
  IO {
    ctx.state.log.info(s"Releasing ${project.name}")
    ctx
  }
}

// Per-project step with cross-build support
val crossTest = perProjectStep("cross-test", enableCrossBuild = true) { (ctx, project) =>
  IO { /* runs once per crossScalaVersions entry */ ctx }
}
```

### Customizing the release process

```scala
// Filter out steps
releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot(_.name == "push-changes")

// Add a step at a specific position
releaseIOMonorepoProcess := {
  val steps = releaseIOMonorepoProcess.value
  val idx = steps.indexWhere(_.name == "tag-releases")
  val (before, after) = steps.splitAt(idx)
  before ++ Seq(validateBranch) ++ after
}

// Replace the entire process
import io.release.monorepo.steps.MonorepoReleaseSteps._

releaseIOMonorepoProcess := Seq(
  initializeVcs,
  checkCleanWorkingDir,
  resolveReleaseOrder,
  detectOrSelectProjects,
  inquireVersions,
  setReleaseVersions,
  commitReleaseVersions,
  tagReleases,
  setNextVersions,
  commitNextVersions
)
```

## Custom Plugins

If your release process needs a shared resource (HTTP client, database connection, temporary directory), create a custom plugin that extends `MonorepoReleasePluginLike[T]`. The resource is acquired once before all steps run and released after they complete (or on failure).

Custom plugins must be defined in `project/*.scala` (not `build.sbt`) because sbt discovers `AutoPlugin` classes during meta-build compilation.

> **Important:** In `project/*.scala` files, `io.release` is shadowed by sbt's `sbt.io` package. Always use `_root_.io.release` and `_root_.cats.effect` imports.

### Creating the plugin

```scala
// project/MyReleasePlugin.scala
import sbt._
import sbt.Keys._
import _root_.io.release.monorepo._
import _root_.cats.effect.{IO, Resource}

object MyReleasePlugin extends MonorepoReleasePluginLike[HttpClient] {
  override def trigger = noTrigger
  override protected def commandName: String = "myRelease"

  override def resource: Resource[IO, HttpClient] =
    Resource.make(IO(new HttpClient()))(c => IO(c.close()))

  override protected def monorepoReleaseProcess(state: State) =
    defaultsWith(state)(
      resourceGlobalStep("notify-slack") { client => ctx =>
        IO.blocking { client.post("/webhook", "Released!"); ctx }
      }
    )
}
```

### Inserting at specific positions

```scala
// Insert after a named step
override protected def monorepoReleaseProcess(state: State) =
  defaultsWithAfter(state, "tag-releases")(
    resourceGlobalStep("post-tag-hook") { client => ctx =>
      IO.blocking { client.post("/hooks/tagged", "done"); ctx }
    }
  )

// Insert before a named step
override protected def monorepoReleaseProcess(state: State) =
  defaultsWithBefore(state, "publish-artifacts")(
    resourcePerProjectStep("pre-publish-check") { client => (ctx, project) =>
      IO.blocking { client.get(s"/ready/${project.name}"); ctx }
    }
  )
```

> **Note:** Each helper inserts at a single position. To insert custom steps at multiple
> non-adjacent positions, use the fully custom approach below.

### Fully custom release process

Override `monorepoReleaseProcess` directly to build the step sequence from scratch instead of
appending to the defaults. Plain steps (from `MonorepoReleaseSteps`) and resource-aware steps
can be mixed freely — an implicit conversion lifts plain steps into resource-ignoring functions.

```scala
// project/MyReleasePlugin.scala
import sbt._
import sbt.Keys._
import _root_.io.release.monorepo._
import _root_.io.release.monorepo.steps.MonorepoReleaseSteps
import _root_.cats.effect.{IO, Resource}

object MyReleasePlugin extends MonorepoReleasePluginLike[HttpClient] {
  override def trigger                    = noTrigger
  override protected def commandName      = "myRelease"
  override def resource: Resource[IO, HttpClient] =
    Resource.make(IO(new HttpClient()))(c => IO(c.close()))

  override protected def monorepoReleaseProcess(state: State): Seq[HttpClient => MonorepoStepIO] =
    Seq(
      // Plain steps — lifted automatically via the implicit conversion
      MonorepoReleaseSteps.initializeVcs,
      MonorepoReleaseSteps.checkCleanWorkingDir,
      // 1st custom step — validate branch before anything else runs
      resourceGlobalStep("validate-branch") { client => ctx =>
        IO.blocking { client.get("/allowed-branches") }.flatMap { branches =>
          if (branches.contains("main")) IO.pure(ctx)
          else IO.raiseError(new RuntimeException("Release blocked"))
        }
      },
      MonorepoReleaseSteps.resolveReleaseOrder,
      MonorepoReleaseSteps.detectOrSelectProjects,
      MonorepoReleaseSteps.inquireVersions,
      MonorepoReleaseSteps.runTests,
      MonorepoReleaseSteps.setReleaseVersions,
      MonorepoReleaseSteps.commitReleaseVersions,
      MonorepoReleaseSteps.tagReleases,
      // 2nd custom step — notify after tagging
      resourceGlobalStep("notify-slack") { client => ctx =>
        IO.blocking { client.post("/webhook", s"Tagged!"); ctx }
      },
      MonorepoReleaseSteps.publishArtifacts,
      // 3rd custom step — verify each project's published artifact
      resourcePerProjectStep("verify-publish") { client => (ctx, project) =>
        IO.blocking { client.get(s"/artifacts/${project.name}"); ctx }
      },
      MonorepoReleaseSteps.setNextVersions,
      MonorepoReleaseSteps.commitNextVersions,
      MonorepoReleaseSteps.pushChanges
    )
}
```

This bypasses the `releaseIOMonorepoProcess` setting entirely — the step list is hard-coded
in the plugin. Use `defaultsWith`, `defaultsWithAfter`, or `defaultsWithBefore` (shown above)
if you want to keep the setting-based defaults and only add extra steps.

### Resource-aware steps with checks

```scala
// Global step with a check phase
resourceGlobalStepWithCheck("validated-push") { client => ctx =>
  IO.blocking { client.post("/push", "pushing"); ctx }
} { client => ctx =>
  IO.blocking { client.get("/can-push"); ctx }
}

// Per-project step with a check phase
resourcePerProjectStepWithCheck("validated-publish", enableCrossBuild = true) {
  client => (ctx, project) =>
    IO.blocking { client.post(s"/publish/${project.name}", "ok"); ctx }
} { client => (ctx, project) =>
    IO.blocking { client.get(s"/can-publish/${project.name}"); ctx }
}
```

### Enabling in build.sbt

```scala
lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MyReleasePlugin)
```

Run with:

```bash
sbt "myRelease with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"
```

## Change Detection

The plugin detects which projects have changed since their last release tag using `git diff`.

### How it works

1. For each project, find the most recent matching tag:
   - **PerProject** strategy: pattern `<projectName>/v*` (e.g., `core/v*`)
   - **Unified** strategy: pattern `v*`
2. If no tag exists, the project is treated as changed (first release).
3. Run `git diff --name-only <tag>..HEAD -- <projectDir>`.
4. Filter out version files and any files in `releaseIOMonorepoDetectChangesExcludes`.
5. If any significant files remain, the project is changed.

Any git command failure conservatively treats the project as changed.

### Custom change detector

```scala
releaseIOMonorepoChangeDetector := Some { (ref: ProjectRef, baseDir: File, state: State) =>
  IO.pure(ref.project == "core") // only release "core"
}
```

On detector error, the project is conservatively treated as changed.

### Excluding files from detection

```scala
// Exclude generated changelog from change detection
releaseIOMonorepoDetectChangesExcludes := Seq(
  baseDirectory.value / "CHANGELOG.md"
)
```

Per-project version files are always excluded automatically.

## Tagging Strategies

### PerProject (default)

Each released project gets its own tag:

```
core/v1.0.0
api/v0.5.0
```

Customize the format:

```scala
releaseIOMonorepoTagName := { (name, ver) => s"release/$name/$ver" }
```

### Unified

A single tag covers the entire release:

```
v1.0.0
```

Requires all projects to have the same release version. The tag annotation lists all project names and versions.

```scala
releaseIOMonorepoTagStrategy    := MonorepoTagStrategy.Unified
releaseIOMonorepoUnifiedTagName := { ver => s"release-v$ver" }
```

## Global Version Mode

When `releaseIOMonorepoUseGlobalVersion := true`:

- All projects share the root `version.sbt` (the file defined by sbt-release's `releaseVersionFile`).
- Version file content uses `ThisBuild / version := "x.y.z"` instead of `version := "x.y.z"`.
- The `validate-version-consistency` step enforces all projects have the same release and next versions.
- Partial project selection is blocked (CLI validation returns an error if a subset is named).
- Change detection must select either all projects or none.

```scala
releaseIOMonorepoUseGlobalVersion := true
```

## Cross-Build Support

When the `cross` flag is active (or `releaseIOMonorepoCrossBuild := true`), steps with `enableCrossBuild = true` run once per entry in the project's `crossScalaVersions`.

Steps with cross-build enabled by default: `check-snapshot-dependencies`, `run-tests`, `publish-artifacts`.

Each project uses its own `crossScalaVersions`. A project with `Seq("2.13.12", "2.12.18")` runs cross-built steps twice; a project with only `Seq("2.12.18")` runs once. Empty `crossScalaVersions` with cross-build enabled raises an error.

## Execution Model

### Two-phase execution

1. **Check phase**: All step checks run against the initial context. State mutations are discarded. Any check failure aborts the entire release before actions execute.
2. **Action phase**: Steps run sequentially, threading `MonorepoContext` through. Between every step, sbt's `FailureCommand` sentinel is inspected for task-level failures.

### Per-project failure isolation

In a monorepo release, multiple sub-projects run through each **PerProject** step in sequence.
If one project's action throws an exception, the plugin **isolates** the failure to that project
so the remaining projects in the same step can still complete.

#### What happens when a per-project action fails

1. The exception is caught and the error message is logged.
2. The project is marked as **failed** internally.
3. The step **continues** executing for the remaining (non-failed) projects.
4. Once the step finishes, the plugin checks whether any project is marked failed.
   If so, the global release context is marked failed and **all subsequent steps**
   (both Global and PerProject) are skipped entirely.
5. At the end of the release, a `RuntimeException("Monorepo release process failed")`
   is raised so the overall sbt command exits with an error.

#### Example

Given three projects — `core`, `api`, and `web` — with the release steps
`run-tests` → `set-release-version` → `publish-artifacts`:

| Step | core | api | web |
|------|------|-----|-----|
| `run-tests` | passes | **fails** | passes |
| `set-release-version` | skipped | skipped | skipped |
| `publish-artifacts` | skipped | skipped | skipped |

- During `run-tests`, `api` throws an exception. The error is logged and `api` is marked
  failed, but `web` still runs and passes.
- After `run-tests` completes, the global context is marked failed because `api` failed.
- `set-release-version` and all later steps are skipped for **every** project.

> **Note:** There is no dependency-aware cascade. If `web` depends on `api`, `web` is not
> automatically marked failed just because `api` failed — it simply continues in the current
> step. In a later step (if the release hadn't been halted), `web` would likely fail on its
> own due to a missing artifact from `api`.

#### Global step failures

A **Global** step failure immediately marks the context as failed and skips all subsequent steps.

### Topological ordering

Projects are sorted by inter-project dependencies using Kahn's algorithm. Dependencies are always released before dependents.

## Compatibility

- **sbt**: 1.x
- **Scala**: 2.12
- **sbt-release**: 1.4.0
- **cats-effect**: 3.6.3
- **Requires**: `sbt-release-io` (core plugin)

## Testing

Run monorepo unit tests:

```bash
sbt monorepo/test
```

Run monorepo scripted integration tests:

```bash
sbt "monorepo/scripted"
```

Run a specific scripted test:

```bash
sbt "monorepo/scripted sbt-release-io-monorepo/simple-monorepo"
```
