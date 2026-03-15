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
  .dependsOn(api)

lazy val api = (project in file("api"))

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
```

Each subproject needs a `version.sbt` file (e.g., `core/version.sbt`, `api/version.sbt`) containing `version := "0.1.0-SNAPSHOT"`. The plugin reads and writes these files during the release. The version file path and format can be customized via `releaseIOMonorepoVersionFile`, `releaseIOMonorepoReadVersion`, and `releaseIOMonorepoWriteVersion` — see [Version settings](#version-settings).

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

## First Release Walkthrough

This walkthrough sets up a two-project monorepo from scratch and runs the first release. It mirrors the `simple-monorepo` scripted test.

### Directory structure

```
my-monorepo/
├── build.sbt
├── project/
│   └── plugins.sbt
├── core/
│   └── version.sbt
└── api/
    └── version.sbt
```

### 1. Add the plugin

`project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.4.2")
```

### 2. Configure the build

`build.sbt`:

```scala
lazy val core = (project in file("core"))
  .settings(name := "core", scalaVersion := "2.12.18")

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(name := "api", scalaVersion := "2.12.18")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    // Filter out push and publish during initial setup — re-enable when ready
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    }
  )
```

### 3. Create version files

`core/version.sbt` and `api/version.sbt`:

```scala
version := "0.1.0-SNAPSHOT"
```

### 4. Initialise git and make the first commit

```bash
git init
git add .
git commit -m "Initial commit"
```

### 5. Run the first release

```bash
sbt "releaseIOMonorepo with-defaults"
```

**What the plugin does:**

1. `initialize-vcs` — detects the git repository.
2. `check-clean-working-dir` — verifies no uncommitted changes.
3. `resolve-release-order` — sorts projects: `core` first, then `api` (because `api` depends on `core`).
4. `detect-or-select-projects` — finds no prior release tags, so **both projects are treated as changed** (first release).
5. `inquire-versions` — computes release version `0.1.0` and next version `0.2.0-SNAPSHOT` for each project.
6. `set-release-version` — writes `version := "0.1.0"` to each `version.sbt`.
7. `commit-release-versions` — creates a single commit staging all version files.
8. `tag-releases` — creates `core/v0.1.0` and `api/v0.1.0`.
9. `set-next-version` — writes `version := "0.2.0-SNAPSHOT"` to each `version.sbt`.
10. `commit-next-versions` — creates a single commit staging all version files.
11. `push-changes` — filtered out in this walkthrough; re-enable once confident.

After the release:

```bash
git log --oneline     # 3 commits: Initial, release versions, next versions
git tag               # core/v0.1.0  api/v0.1.0
cat core/version.sbt  # version := "0.2.0-SNAPSHOT"
```

> **Note:** The first release triggers all projects as changed because change detection looks for a prior release tag and finds none. On subsequent runs, only projects with file changes since their last tag are released. To force all projects regardless, use the `all-changed` flag.

## Dry Run

Filter out the steps that push commits and publish artifacts to rehearse the release process without side effects.

```scala
// build.sbt — temporary dry-run configuration
releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}
```

Then run normally:

```bash
sbt "releaseIOMonorepo with-defaults"
```

The release creates version commits and tags locally without touching the remote or any artifact repository. Inspect the result:

```bash
git log --oneline
git tag
cat core/version.sbt
```

To undo the dry run, see [Recovery and Rollback](#recovery-and-rollback).

## Default Release Steps

| # | Step | Type | Description |
|---|------|------|-------------|
| 1 | `initialize-vcs` | Global | Detect git, store VCS adapter in context |
| 2 | `check-clean-working-dir` | Global | Validation-only step that fails if uncommitted changes exist |
| 3 | `resolve-release-order` | Global | Topologically sort projects by dependencies |
| 4 | `detect-or-select-projects` | Global | Run change detection or use explicit CLI selection |
| 5 | `check-snapshot-dependencies` | PerProject | Validation-only step that fails if any SNAPSHOT dependencies are found (cross-build) |
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
execute steps resolve project order, project selection, version-file handling, and tag settings from the
current `State` when they run. Custom steps now use the public `validate`/`execute` model directly.

## Configuration

Settings prefixed `releaseIO` (no `Monorepo`) come from the **core plugin** (`sbt-release-io`) and are
available whenever that plugin is on the classpath. Settings prefixed `releaseIOMonorepo` come from
**this plugin**. Several monorepo settings shadow their core counterpart with a different default — for
example `releaseIOMonorepoVersionFile` resolves per-project files, while `releaseIOVersionFile` is a
single root-project file. Always configure the `releaseIOMonorepo*` variant when using the monorepo plugin.

### Core settings

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoProjects` | `SettingKey[Seq[ProjectRef]]` | All transitively aggregated subprojects | Which subprojects participate in releases |
| `releaseIOMonorepoProcess` | `SettingKey[Seq[MonorepoStepIO]]` | `MonorepoReleaseSteps.defaults` | Ordered release steps |
| `releaseIOMonorepoCrossBuild` | `SettingKey[Boolean]` | `false` | Enable cross-building by default |
| `releaseIOMonorepoSkipTests` | `SettingKey[Boolean]` | `false` | Skip tests |
| `releaseIOMonorepoSkipPublish` | `SettingKey[Boolean]` | `false` | Skip publish |
| `releaseIOMonorepoInteractive` | `SettingKey[Boolean]` | `false` | Enable interactive version prompts |

### Version settings

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoVersionFile` | `SettingKey[MonorepoVersionFileResolver]` | Scoped `releaseIOVersionFile` | Per-project version file resolver `(ProjectRef, State) => File` |
| `releaseIOMonorepoReadVersion` | `SettingKey[File => IO[String]]` | Regex parser (same as core) | Version file reader |
| `releaseIOMonorepoWriteVersion` | `SettingKey[(File, String) => IO[String]]` | `version := "x.y.z"\n` | Version file writer (default ignores `File` param) |
| `releaseIOMonorepoUseGlobalVersion` | `SettingKey[Boolean]` | `false` | Use root `version.sbt` instead of per-project files |

### Tagging settings

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoTagStrategy` | `SettingKey[MonorepoTagStrategy]` | `PerProject` | `PerProject` or `Unified` |
| `releaseIOMonorepoTagName` | `SettingKey[(String, String) => String]` | `(name, ver) => s"$name/v$ver"` | Per-project tag formatter |
| `releaseIOMonorepoUnifiedTagName` | `SettingKey[String => String]` | `ver => s"v$ver"` | Unified tag formatter |

### Change detection settings

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoDetectChanges` | `SettingKey[Boolean]` | `true` | Enable git-based change detection |
| `releaseIOMonorepoIncludeDownstream` | `SettingKey[Boolean]` | `false` | Include transitive downstream dependents of changed projects |
| `releaseIOMonorepoChangeDetector` | `SettingKey[Option[(ProjectRef, File, State) => IO[Boolean]]]` | `None` | Custom change detector |
| `releaseIOMonorepoDetectChangesExcludes` | `SettingKey[Seq[File]]` | `Seq.empty` | Files to exclude from detection |
| `releaseIOMonorepoSharedPaths` | `SettingKey[Seq[String]]` | `Seq("build.sbt", "project/")` | Root-level paths checked for shared changes per project |

Files matching `releaseIOMonorepoSharedPaths` (relative to the repo root) are checked against each project's own last release tag. If any shared file changed since that tag, the project is marked as changed. This ensures modifications to shared build definitions, compiler plugins, or dependency versions are never silently missed. In per-project tag mode, projects with different tags are evaluated independently — a project tagged after a shared change won't be marked changed, while one tagged before it will. Set to `Seq.empty` to disable.

```scala
// Add extra shared paths (e.g. a shared source directory and formatting config)
releaseIOMonorepoSharedPaths := Seq("build.sbt", "project/", "shared/", ".scalafmt.conf")

// Or disable shared path detection entirely
releaseIOMonorepoSharedPaths := Seq.empty
```

### Example configuration

> The explicit `import io.release.monorepo.MonorepoReleasePlugin.autoImport.*` is optional — sbt auto-imports these keys from plugins on the classpath.

```scala
import io.release.monorepo.MonorepoReleasePlugin.autoImport.*

releaseIOMonorepoSkipTests   := true
releaseIOMonorepoCrossBuild  := true
releaseIOMonorepoTagStrategy := MonorepoTagStrategy.Unified
releaseIOMonorepoTagName     := { (name, ver) => s"release/$name/$ver" }
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

### Downstream dependents

By default, only projects with direct file changes are released. If `core` changes and `api` depends on `core`, `api` is **not** released unless it also has file changes.

Enable `releaseIOMonorepoIncludeDownstream` to automatically include all transitive downstream dependents of changed projects:

```scala
releaseIOMonorepoIncludeDownstream := true
```

With this setting, if `core` changes and `api` depends on `core` and `web` depends on `api`, all three are released. This works with both the built-in git-based detector and custom change detectors.

### Version overrides force-include projects

When using change detection, providing a CLI version override for a project forces it into the release set even if it has no detected changes. For example:

```bash
sbt "releaseIOMonorepo with-defaults release-version api=1.0.0 next-version api=1.1.0-SNAPSHOT"
```

This releases `api` at version `1.0.0` regardless of whether change detection found changes in `api`. Note that force-included projects do not trigger downstream expansion — only projects detected as changed (by git diff or a custom detector) contribute to downstream expansion when `releaseIOMonorepoIncludeDownstream` is enabled.

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

- All projects share the root `version.sbt` (the file defined by `releaseIOVersionFile`).
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

## Custom Steps

### Step context API

Global steps receive a `MonorepoContext`; per-project steps receive both `MonorepoContext` and `ProjectReleaseInfo`.

**`MonorepoContext`** — immutable context threaded through all steps:

| Field / Method | Type | Description |
|----------------|------|-------------|
| `state` | `State` | Current sbt state |
| `vcs` | `Option[Vcs]` | Git adapter, set by `initialize-vcs` |
| `projects` | `Seq[ProjectReleaseInfo]` | Selected projects in topological order |
| `currentProjects` | `Seq[ProjectReleaseInfo]` | Non-failed projects only |
| `skipTests` / `skipPublish` / `interactive` | `Boolean` | Execution flags |
| `tagStrategy` | `MonorepoTagStrategy` | `PerProject` or `Unified` |
| `failed` | `Boolean` | Whether the release has failed |
| `withState(s)` | `MonorepoContext` | Replace sbt state |
| `withProjects(ps)` | `MonorepoContext` | Replace project list |
| `updateProject(ref)(f)` | `MonorepoContext` | Transform a single project's info |
| `metadata[A](key)` | `Option[A]` | Read typed inter-step metadata |
| `withMetadata[A](key, value)` | `MonorepoContext` | Store typed inter-step metadata |

**`ProjectReleaseInfo`** — per-project metadata available in `PerProject` steps:

| Field / Method | Type | Description |
|----------------|------|-------------|
| `ref` | `ProjectRef` | sbt project reference |
| `name` | `String` | Project name (matches `ref.project`) |
| `baseDir` | `File` | Project root directory |
| `versionFile` | `File` | Resolved version file path |
| `versions` | `Option[(String, String)]` | `(releaseVersion, nextVersion)`, set by `inquire-versions` |
| `releaseVersion` | `Option[String]` | Shorthand for `versions.map(_._1)` |
| `nextVersion` | `Option[String]` | Shorthand for `versions.map(_._2)` |
| `tagName` | `Option[String]` | Tag name, set by `tag-releases` |
| `failed` | `Boolean` | Whether this project failed |

### Factory methods

**Plain steps** (for `build.sbt` or `project/*.scala`):

| Method | Scope | Execute returns |
|--------|-------|-----------------|
| `globalStep(name)(execute)` | Global | `IO[MonorepoContext]` |
| `perProjectStep(name, enableCrossBuild)(execute)` | PerProject | `IO[MonorepoContext]` |
| `globalStepAction(name)(execute)` | Global | `IO[Unit]` |
| `perProjectStepAction(name, enableCrossBuild)(execute)` | PerProject | `IO[Unit]` |

**Process helpers** (for `build.sbt` or `project/*.scala`):

| Method | Description |
|--------|-------------|
| `insertStepAfter(steps, name)(extra)` | Insert steps after the named step |
| `insertStepBefore(steps, name)(extra)` | Insert steps before the named step |

**Resource-aware steps** (for `project/*.scala` custom plugins):

| Method | Scope | Execute returns |
|--------|-------|-----------------|
| `resourceGlobalStep(name)(f)` | Global | `IO[MonorepoContext]` |
| `resourcePerProjectStep(name, enableCrossBuild)(f)` | PerProject | `IO[MonorepoContext]` |
| `resourceGlobalStepWithValidation(name)(execute)(validate)` | Global | `IO[MonorepoContext]` |
| `resourcePerProjectStepWithValidation(name, enableCrossBuild)(execute)(validate)` | PerProject | `IO[MonorepoContext]` |

**Resource-aware action steps** (execute returns `IO[Unit]`, context passed through unchanged):

| Method | Scope |
|--------|-------|
| `resourceGlobalStepAction(name)(f)` | Global |
| `resourcePerProjectStepAction(name, enableCrossBuild)(f)` | PerProject |
| `resourceGlobalStepActionWithValidation(name)(execute)(validate)` | Global |
| `resourcePerProjectStepActionWithValidation(name, enableCrossBuild)(execute)(validate)` | PerProject |

```scala
import cats.effect.IO
import io.release.monorepo.MonorepoReleasePlugin.autoImport.*

// Global step — runs once
val validateBranch = globalStep("validate-branch") { ctx =>
  ctx.vcs match {
    case Some(vcs) =>
      vcs.currentBranch.flatMap { branch =>
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

### Sharing data between steps

Use `ctx.updateProject(ref)(_.copy(...))` to update a single project's metadata from within a step.
This is the per-project complement to `ctx.withMetadata` / `ctx.metadata`, which store global (non-project-scoped) values.

```scala
// Global step: after inquire-versions has run, log every project's planned release version
val logPlannedVersions = globalStepAction("log-planned-versions") { ctx =>
  IO {
    ctx.currentProjects.foreach { p =>
      p.releaseVersion match {
        case Some(v) => ctx.state.log.info(s"[release] ${p.name} → $v")
        case None    => ctx.state.log.warn(s"[release] ${p.name} — no release version set yet")
      }
    }
  }
}

// Global step: forcibly pin the release version for one project
val pinCoreVersion = globalStep("pin-core-version") { ctx =>
  IO.pure(
    ctx.projects.find(_.name == "core").fold(ctx) { core =>
      ctx.updateProject(core.ref)(
        _.copy(versions = Some(("2.0.0", "2.1.0-SNAPSHOT")))
      )
    }
  )
}

// Global step: bulk-transform all projects using foldLeft
val transformAll = globalStep("transform-all") { ctx =>
  IO.pure(
    ctx.currentProjects.foldLeft(ctx) { (c, p) =>
      c.updateProject(p.ref)(_.copy(/* your transformation here */))
    }
  )
}
```

For global (non-project-scoped) data shared across steps, use typed metadata:

```scala
private val myKey = AttributeKey[String]("myKey")

val writeStep = globalStep("write-metadata") { ctx =>
  IO.pure(ctx.withMetadata(myKey, "hello"))
}

val readStep = globalStepAction("read-metadata") { ctx =>
  IO(ctx.state.log.info(ctx.metadata[String](myKey).getOrElse("(not set)")))
}
```

### Customizing the release process

```scala
// Filter out steps
releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot(_.name == "push-changes")

// Add a step at a specific position
releaseIOMonorepoProcess := insertStepBefore(releaseIOMonorepoProcess.value, "tag-releases")(
  Seq(validateBranch)
)

// Replace the entire process (step vals from MonorepoReleaseSteps)
import io.release.monorepo.steps.MonorepoReleaseSteps.*

releaseIOMonorepoProcess := Seq(
  initializeVcs, checkCleanWorkingDir, resolveReleaseOrder,
  detectOrSelectProjects, checkSnapshotDependencies, inquireVersions,
  // validateVersions, runClean, runTests, and publishArtifacts omitted — tag-only release
  setReleaseVersions, commitReleaseVersions, tagReleases,
  setNextVersions, commitNextVersions, pushChanges
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
import sbt.*
import _root_.io.release.monorepo.*
import _root_.io.release.monorepo.MonorepoReleaseIO.*
import _root_.cats.effect.{IO, Resource}

object MyReleasePlugin extends MonorepoReleasePluginLike[HttpClient] {
  override def trigger = noTrigger
  override protected def commandName: String = "myRelease"

  override def resource: Resource[IO, HttpClient] =
    Resource.make(IO(new HttpClient()))(c => IO(c.close()))

  override protected def monorepoReleaseProcess(state: State) =
    liftSteps(Project.extract(state).get(releaseIOMonorepoProcess)) :+
      resourceGlobalStepAction("notify-slack") { client => ctx =>
        IO.blocking { client.post("/webhook", "Released!") }
      }
}
```

Use `resourceGlobalStep` instead when the step needs to modify the context.

### Inserting at specific positions

```scala
// Insert after a named step
override protected def monorepoReleaseProcess(state: State) =
  insertAfter(Project.extract(state).get(releaseIOMonorepoProcess), "tag-releases")(
    Seq(resourceGlobalStepAction("post-tag-hook") { client => ctx =>
      IO.blocking { client.post("/hooks/tagged", "done") }
    })
  )

// Insert before a named step
override protected def monorepoReleaseProcess(state: State) =
  insertBefore(Project.extract(state).get(releaseIOMonorepoProcess), "publish-artifacts")(
    Seq(resourcePerProjectStepAction("pre-publish-check") { client => (ctx, project) =>
      IO.blocking { client.get(s"/ready/${project.name}") }
    })
  )
```

`insertAfter` and `insertBefore` match the exact `step.name` strings shown in
the default-step table above, such as `"tag-releases"` or `"publish-artifacts"`.

Custom steps inserted before built-in monorepo execute steps may update session settings in `State`, and
later built-in execute steps will read those live settings when they run. This applies to built-in order
resolution, project selection, version resolution, and tagging. Custom `PerProject` steps still use
the current `MonorepoContext.projects` snapshot unless they explicitly replace it themselves, and
built-in validation still runs against the setup-selected snapshot rather than threading later context changes backward.

#### Custom step timing

- The step list is frozen when the command starts.
- Built-in **Global** execute steps such as `resolve-release-order` and `detect-or-select-projects`
  read the current `State` when they run.
- Built-in **validate** functions after `detect-or-select-projects` run against the selected snapshot.
- Custom `PerProject` steps keep using `ctx.projects` until you replace that snapshot yourself.

Example: rewrite the project set via `State` so built-in steps see the change:

```scala
// Inside a MonorepoReleasePluginLike[Unit] plugin
private val selectOnlyCore = MonorepoStepIO.Global("select-only-core") { ctx =>
  IO.blocking {
    val extracted    = Project.extract(ctx.state)
    val root         = extracted.get(baseDirectory)
    val updatedState = extracted.appendWithSession(
      Seq(releaseIOMonorepoProjects := Seq(ProjectRef(root, "core"))),
      ctx.state
    )
    ctx.withState(updatedState)
  }
}

override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
  insertBefore(Project.extract(state).get(releaseIOMonorepoProcess), "resolve-release-order")(
    Seq((_: Unit) => selectOnlyCore)
  )
```

Example: update `ctx.projects` directly for later custom `PerProject` steps:

```scala
private val keepLibrariesOnly = MonorepoStepIO.Global("keep-libraries-only") { ctx =>
  IO.pure(ctx.withProjects(ctx.projects.filter(_.name.startsWith("lib-"))))
}

override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
  insertAfter(Project.extract(state).get(releaseIOMonorepoProcess), "detect-or-select-projects")(
    Seq((_: Unit) => keepLibrariesOnly)
  )
```

Use the first pattern when later built-in steps should see new settings from `State`. Use the second pattern when later custom `PerProject` steps should iterate a different project set.

> **Note:** Each helper inserts at a single position. To insert custom steps at multiple
> non-adjacent positions, use the fully custom approach below.

### Fully custom release process

Override `monorepoReleaseProcess` directly to build the step sequence from scratch instead of
appending to the defaults. Plain steps (from `MonorepoReleaseSteps`) and resource-aware steps
can be mixed freely — an implicit conversion lifts plain steps into resource-ignoring functions.

```scala
// Inside a MonorepoReleasePluginLike[HttpClient] plugin
override protected def monorepoReleaseProcess(state: State): Seq[HttpClient => MonorepoStepIO] =
  Seq(
    // Plain steps — lifted automatically via implicit conversion
    MonorepoReleaseSteps.initializeVcs,
    MonorepoReleaseSteps.checkCleanWorkingDir,
    // Custom step interleaved with defaults
    resourceGlobalStep("validate-branch") { client => ctx =>
      IO.blocking { client.get("/allowed-branches") }.flatMap { branches =>
        if (branches.contains("main")) IO.pure(ctx)
        else IO.raiseError(new RuntimeException("Release blocked"))
      }
    },
    MonorepoReleaseSteps.resolveReleaseOrder,
    // ... remaining default steps ...
    MonorepoReleaseSteps.tagReleases,
    resourceGlobalStepAction("notify-slack") { client => ctx =>
      IO.blocking { client.post("/webhook", s"Tagged!") }
    },
    MonorepoReleaseSteps.publishArtifacts,
    // ... remaining default steps ...
  )
```

This bypasses the `releaseIOMonorepoProcess` setting — the step list is hard-coded in the plugin. Use `liftSteps`, `insertAfter`, or `insertBefore` (shown above) to keep the setting-based defaults and only add extra steps.

### Validation and action variants

Steps with a validation phase run validation before execute. Validation may fail the release but does not thread context. Action variants return `IO[Unit]` instead of `IO[MonorepoContext]`, avoiding the `; ctx` boilerplate:

```scala
// Step with validation — execute returns IO[MonorepoContext]
resourceGlobalStepWithValidation("validated-push") { client => ctx =>
  IO.blocking { client.post("/push", "pushing"); ctx }
} { client => ctx =>
  IO.blocking { client.get("/can-push") }.void
}

// Action with validation — execute returns IO[Unit], context passed through
resourceGlobalStepActionWithValidation("validated-notify") { client => ctx =>
  IO.blocking { client.post("/notify", "done") }
} { client => ctx =>
  IO.blocking { client.get("/can-notify") }.void
}
```

Per-project equivalents (`resourcePerProjectStepWithValidation`, `resourcePerProjectStepActionWithValidation`) follow the same pattern with an additional `(ctx, project)` parameter.

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

## Common Pitfalls

### `import sbt.*` shadows the `io` package in `project/*.scala`

In `project/*.scala` files, `import sbt.*` brings in `sbt.io`, shadowing the top-level `io` package.
Custom plugin imports like `import io.release.monorepo.*` fail with:

```
[error] not found: value io
[error]   import io.release.monorepo.*
```

**Fix:** prefix `io.release` and `cats.effect` with `_root_`:

```scala
// project/MyReleasePlugin.scala
import sbt.*
import _root_.io.release.monorepo.*
import _root_.io.release.monorepo.MonorepoReleaseIO.*
import _root_.cats.effect.{IO, Resource}
```

This only applies in `project/*.scala`. In `build.sbt`, unqualified `io.release` imports work correctly.

### Duplicate `autoImport` causes ambiguous reference errors

If a custom plugin defines `object autoImport`, sbt sees two plugins exporting the same keys and
any reference to `releaseIOMonorepoProcess` (or any other shared key) fails with:

```
[error] reference to releaseIOMonorepoProcess is ambiguous
```

**Fix:** do not define `object autoImport` in custom plugins that extend `MonorepoReleasePluginLike`.
The keys exported by `MonorepoReleasePlugin.autoImport` are already in scope everywhere.

### Custom plugin not found at build time

Plugins that extend `MonorepoReleasePluginLike[T]` must be in `project/*.scala`. sbt only discovers
`AutoPlugin` subclasses during meta-build compilation. A plugin class placed in `build.sbt` will not
be found.

### First release shows no projects changed

On a brand-new repo with no prior release tags, change detection marks all projects as changed — this
is expected. If tags exist but under a different scheme, some projects may appear unchanged. Use
`all-changed` to bypass detection and release everything:

```bash
sbt "releaseIOMonorepo all-changed with-defaults"
```

Or disable change detection permanently:

```scala
releaseIOMonorepoDetectChanges := false
```

## Execution Model

### Validate / Execute Model

1. **Setup segment**: Steps up to and including the first `detect-or-select-projects` run as `validate` then `execute` sequentially. This is the boundary where built-in order and selection can be reshaped from live `State`.
2. **Main validation**: Remaining step validation runs against the selected project snapshot produced by setup.
3. **Main execution**: Remaining steps run sequentially, threading `MonorepoContext` through. Built-in execute steps resolve project order, selection, version-file settings, and tag settings from the current `State` when they run. Between every step, sbt's `FailureCommand` sentinel is inspected for task-level failures.

### Per-project failure isolation

In a monorepo release, multiple sub-projects run through each **PerProject** step in sequence.
If one project's execute function throws an exception, the plugin **isolates** the failure to that project
so the remaining projects in the same step can still complete.

#### What happens when a per-project execute fails

1. The exception is caught and the error message is logged.
2. The project is marked as **failed** internally.
3. The step **continues** executing for the remaining (non-failed) projects.
4. Once the step finishes, the plugin checks whether any project is marked failed.
   If so, the global release context is marked failed and **all subsequent steps**
   (both Global and PerProject) are skipped entirely.
5. At the end of the release, the overall failure keeps a `MonorepoProjectFailures` cause so the per-project root exceptions remain available.

Given three projects — `core`, `api`, and `web` — with the release steps
`run-tests` → `set-release-version` → `publish-artifacts`:

| Step | core | api | web |
|------|------|-----|-----|
| `run-tests` | passes | **fails** | passes |
| `set-release-version` | skipped | skipped | skipped |
| `publish-artifacts` | skipped | skipped | skipped |

During `run-tests`, `api` throws an exception. The error is logged and `api` is marked failed, but `web` still runs and passes. After `run-tests` completes, the global context is marked failed because `api` failed. All later steps are skipped for every project.

> **Note:** There is no dependency-aware cascade. If `web` depends on `api`, `web` is not automatically marked failed — it continues in the current step.

A **Global** step failure immediately marks the context as failed and skips all subsequent steps.

### Topological ordering

Projects are sorted by inter-project dependencies using Kahn's algorithm. Dependencies are always released before dependents.

## Recovery and Rollback

### What each release phase modifies

| Step | Modifies |
|------|----------|
| `set-release-version` | Per-project `version.sbt` files (working tree) |
| `commit-release-versions` | Local git history — one commit |
| `tag-releases` | Local git tags |
| `publish-artifacts` | Remote artifact repository |
| `set-next-version` | Per-project `version.sbt` files (working tree) |
| `commit-next-versions` | Local git history — one commit |
| `push-changes` | Remote git branch and tags |

### Checking current state

```bash
git log --oneline -5   # see what commits the release made
git tag                # see what tags were created
cat core/version.sbt   # inspect a version file
```

### Rollback: push has not happened

```bash
# Delete tags created by tag-releases
git tag -d core/v0.1.0
git tag -d api/v0.1.0

# Undo commits (2 = commit-release-versions + commit-next-versions)
git reset --hard HEAD~2
```

If the release failed before `commit-next-versions` (only one commit was made):

```bash
git tag -d core/v0.1.0
git tag -d api/v0.1.0
git reset --hard HEAD~1
```

### Rollback: push has already happened

```bash
# Delete remote tags
git push origin :refs/tags/core/v0.1.0
git push origin :refs/tags/api/v0.1.0

# Safe revert (keeps history)
git revert HEAD     # revert commit-next-versions
git revert HEAD~1   # revert commit-release-versions
git push origin main
```

> **Note:** Published artifacts cannot be retracted from most repositories. Publish a corrected patch release instead.

## Migrating Custom Steps

If you are updating a custom plugin or build from an older release:

- rename `check` to `validate`
- rename `action` to `execute`
- rename `resourceGlobalStepWithCheck` to `resourceGlobalStepWithValidation`
- rename `resourcePerProjectStepWithCheck` to `resourcePerProjectStepWithValidation`
- replace `withAttr` / `attr` string keys with typed metadata via `withMetadata`, `metadata`, and `AttributeKey[A]`

## Compatibility

- **sbt**: 1.12.3 and 2.0.0-RC9
- **Scala**: 2.12.21 and 3.8.1
- **cats-effect**: 3.6.3
- **VCS**: Git only
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

See `src/sbt-test/README.md` for test documentation.

## Contributing

Contributions are welcome! Please ensure:

1. All tests pass (`sbt monorepo/scripted`)
2. Code compiles (`sbt compile`)
3. No breaking changes to public API
4. Add tests for new features

## License

Apache License 2.0

## Acknowledgments

Extends [sbt-release-io](../core/README.md) with monorepo support for per-project versioning, change detection, and failure isolation.
