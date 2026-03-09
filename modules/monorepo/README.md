# sbt-release-io-monorepo

[![Maven Central](https://img.shields.io/maven-central/v/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)](https://central.sonatype.com/artifact/io.github.scalauser12/sbt-release-io-monorepo_2.12_1.0)

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
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.4.2")
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

In global version mode, use global overrides (without `project=`) to apply the same version to all projects:

```
release-version <version>
next-version <version>
```

Per-project overrides are not allowed in global version mode. Global overrides are not allowed in non-global mode.

### Examples

```bash
# Release all changed projects with default versions
sbt "releaseIOMonorepo with-defaults"

# Release only core and api
sbt "releaseIOMonorepo core api with-defaults"

# Pin versions per project
sbt "releaseIOMonorepo with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"

# Pin versions globally (global version mode only)
sbt "releaseIOMonorepo with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"

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
| 7 | `validate-versions` | Global | Fail if global-version mode is active but versions are inconsistent |
| 8 | `run-clean` | PerProject | Clean selected project outputs; sbt 2 stays on project-scoped `clean` because `cleanFull` is build-wide |
| 9 | `run-tests` | PerProject | Run the selected project's `test` task (cross-build enabled, skippable) |
| 10 | `set-release-version` | PerProject | Write release version to `version.sbt` |
| 11 | `commit-release-versions` | Global | Single commit staging all version files |
| 12 | `tag-releases` | Global | Create per-project or unified tags |
| 13 | `publish-artifacts` | PerProject | Publish the selected project's artifacts (cross-build enabled, skippable) |
| 14 | `set-next-version` | PerProject | Write next snapshot version to `version.sbt` |
| 15 | `commit-next-versions` | Global | Single commit staging all version files |
| 16 | `push-changes` | Global | Push branch + tags to tracking remote |

**Global** steps run once. **PerProject** steps run once per selected project in topological order.
Built-in task-backed per-project steps are project-scoped: child projects run only when they are themselves selected or discovered.
Command-line flags and CLI override syntax are validated before execution begins, but built-in
actions resolve project order, project selection, version-file handling, and tag settings from the
current `State` when they run. The public check/action step model still remains for compatibility,
so check-phase context mutations are discarded.

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
| `releaseIOMonorepoVersionFile` | `MonorepoVersionFileResolver` | Scoped `releaseVersionFile` | Per-project version file resolver `(ProjectRef, State) => File` |
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
| `releaseIOMonorepoSharedPaths` | `Seq[String]` | `Seq("build.sbt", "project/")` | Root-level paths checked for shared changes per project |

Files matching `releaseIOMonorepoSharedPaths` (relative to the repo root) are checked against each project's own last release tag. If any shared file changed since that tag, the project is marked as changed. This ensures modifications to shared build definitions, compiler plugins, or dependency versions are never silently missed. In per-project tag mode, projects with different tags are evaluated independently — a project tagged after a shared change won't be marked changed, while one tagged before it will. Set to `Seq.empty` to disable.

```scala
// Add extra shared paths (e.g. a shared source directory and formatting config)
releaseIOMonorepoSharedPaths := Seq("build.sbt", "project/", "shared/", ".scalafmt.conf")

// Or disable shared path detection entirely
releaseIOMonorepoSharedPaths := Seq.empty
```

### Example configuration

> The explicit `import io.release.monorepo.MonorepoReleasePlugin.autoImport._` is optional — sbt auto-imports these keys from plugins on the classpath.

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
  checkSnapshotDependencies,
  inquireVersions,
  // runClean and runTests omitted
  setReleaseVersions,
  commitReleaseVersions,
  tagReleases,
  // publishArtifacts omitted — tag-only release
  setNextVersions,
  commitNextVersions,
  pushChanges
)
```

## Custom Plugins

If your release process needs a shared resource (HTTP client, database connection, temporary directory), create a custom plugin that extends `MonorepoReleasePluginLike[T]`. The resource is acquired once before all steps run and released after they complete (or on failure).

When using a custom plugin, you only need `enablePlugins(MyReleasePlugin)` — you do not need to enable `MonorepoReleasePlugin`. Keys like `releaseIOMonorepoProcess` are available automatically because sbt imports `autoImport` from all plugins on the classpath, regardless of whether they are enabled. Plugin enablement controls which settings are applied, not which keys are in scope.

> **Do not add `object autoImport`** to custom plugins. When both `MonorepoReleasePlugin` and a custom plugin define `autoImport`, the build gets ambiguous references (e.g. `reference to releaseIOMonorepoProcess is ambiguous`).

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

`defaultsWithAfter` and `defaultsWithBefore` match the exact `step.name` strings shown in
the default-step table above, such as `"tag-releases"` or `"publish-artifacts"`.

Custom steps inserted before built-in monorepo actions may update session settings in `State`, and
later built-in actions will read those live settings when they run. This applies to built-in order
resolution, project selection, version resolution, and tagging. Custom `PerProject` steps still use
the current `MonorepoContext.projects` snapshot unless they explicitly replace it themselves, and
built-in checks still run from the initial check-phase state.

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

Checks run as real effects before any actions execute. Only the returned context/state is
discarded after the check phase; external side effects are not rolled back. Custom checks
should therefore be side-effect free and safe to run more than once.

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

> **Note:** A custom detector **replaces** the built-in detection entirely. Settings like
> `releaseIOMonorepoDetectChangesExcludes` only apply to the built-in detector and are
> ignored when a custom detector is set.

### Excluding files from detection

```scala
// In the root project settings — exclude a subproject's generated changelog
releaseIOMonorepoDetectChangesExcludes := Seq(
  (core / baseDirectory).value / "CHANGELOG.md"
)
```

This setting is read from the **root project** scope, so use `(subproject / baseDirectory).value`
to reference subproject directories. Per-project version files are always excluded automatically.
This setting only applies to the built-in detector and is ignored when `releaseIOMonorepoChangeDetector` is set.

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

> **Note:** The version consistency check applies only to the projects selected for release. When change detection is enabled, unchanged projects are excluded, so partial releases are possible — the unified tag will only reflect the changed subset. For true all-or-nothing unified releases, combine with Global Version mode or use the `all-changed` flag.

```scala
releaseIOMonorepoTagStrategy    := MonorepoTagStrategy.Unified
releaseIOMonorepoUnifiedTagName := { ver => s"release-v$ver" }
```

## Global Version Mode

When `releaseIOMonorepoUseGlobalVersion := true`:

- All projects share the root `version.sbt` (the file defined by sbt-release's `releaseVersionFile`).
- Version file content uses `ThisBuild / version := "x.y.z"` instead of `version := "x.y.z"`.
- Per-project version overrides are rejected at parse time; use global overrides instead (`release-version 1.0.0`).
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
2. **Action phase**: Steps run sequentially, threading `MonorepoContext` through. Built-in actions resolve project order, selection, version-file settings, and tag settings from the current `State` when they run. Between every step, sbt's `FailureCommand` sentinel is inspected for task-level failures.

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

- **sbt**: 1.12.3 and 2.0.0-RC9
- **Scala**: 2.12.21 and 3.8.1
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
