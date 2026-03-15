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

Each subproject needs a `version.sbt` file (e.g., `core/version.sbt`, `api/version.sbt`) containing `version := "0.1.0-SNAPSHOT"`. The plugin reads and writes these files during the release. The file path and format can be customized — see [Version settings](#version-settings).

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
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot(step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    )
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

The plugin runs the [default release steps](#default-release-steps) in order — sorting projects by dependency, computing versions, writing version files, committing, and tagging. Push is filtered out in this walkthrough; re-enable once confident.

After the release:

```bash
git log --oneline     # 3 commits: Initial, release versions, next versions
git tag               # core/v0.1.0  api/v0.1.0
cat core/version.sbt  # version := "0.2.0-SNAPSHOT"
```

> **Note:** The first release triggers all projects as changed because change detection looks for a prior release tag and finds none. On subsequent runs, only projects with file changes since their last tag are released. To force all projects regardless, use the `all-changed` flag.

> **Dry run:** The walkthrough above already filters out `push-changes` and `publish-artifacts`. Use this same `filterNot` pattern to rehearse any release without side effects. To undo a dry run, see [Recovery and Rollback](#recovery-and-rollback).

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

**Global** steps run once. **PerProject** steps run once per selected project in topological order. Only selected projects participate — child projects that weren't selected or discovered by change detection are skipped.

## Execution Model

### Validate / Execute Model

1. **Setup segment**: Steps up to and including `detect-or-select-projects` run validate-then-execute sequentially. Custom steps inserted here can modify project ordering and selection before the main phase begins.
2. **Main validation**: Remaining step validation runs against the selected project snapshot produced by setup.
3. **Main execution**: Remaining steps run sequentially, threading `MonorepoContext` through. Task-level failures are detected between steps.

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
5. At the end of the release, the overall failure keeps a `MonorepoProjectFailures` cause so the per-project root exceptions remain available. This exception contains a `Seq[MonorepoProjectFailure]`, each with `projectName: String` and `cause: Option[Throwable]`.

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

## Configuration

Settings prefixed `releaseIO` (no `Monorepo`) come from the **core plugin** (`sbt-release-io`) and are
available whenever that plugin is on the classpath. Settings prefixed `releaseIOMonorepo` come from
**this plugin**. Several monorepo settings mirror their core counterpart with a different default — for
example `releaseIOMonorepoVersionFile` resolves per-project files, while `releaseIOVersionFile` is a
single root-project file. Always configure the `releaseIOMonorepo*` variant when using the monorepo plugin.

### Core settings

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoProjects` | `Seq[ProjectRef]` | All transitively aggregated subprojects | Which subprojects participate in releases |
| `releaseIOMonorepoProcess` | `Seq[MonorepoStepIO]` | `MonorepoReleaseSteps.defaults` | Ordered release steps |
| `releaseIOMonorepoCrossBuild` | `Boolean` | `false` | Enable cross-building by default |
| `releaseIOMonorepoSkipTests` | `Boolean` | `false` | Skip tests |
| `releaseIOMonorepoSkipPublish` | `Boolean` | `false` | Skip publish |
| `releaseIOMonorepoInteractive` | `Boolean` | `false` | When true, `inquire-versions` prompts interactively. `with-defaults` overrides to false; CLI version overrides bypass prompts for those projects. |
| `releaseIOMonorepoPublishArtifactsChecks` | `Boolean` | `true` | When false, skips the check that each project has `publishTo` configured or `publish / skip := true` |

### Version settings

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoVersionFile` | `MonorepoVersionFileResolver` | Scoped `releaseIOVersionFile` | Per-project version file resolver `(ProjectRef, State) => File`. Called during version inquiry and write steps. Default reads each project's scoped `releaseIOVersionFile` (typically `<projectDir>/version.sbt`). |
| `releaseIOMonorepoReadVersion` | `File => IO[String]` | Regex parser (same as core) | Version file reader |
| `releaseIOMonorepoVersionFileContents` | `(File, String) => IO[String]` | `version := "x.y.z"\n` | Returns the version file content to write to disk. The `File` arg is the current version file, available for reading existing content before writing (e.g., partial updates); the default ignores it. |
| `releaseIOMonorepoUseGlobalVersion` | `Boolean` | `false` | Use root `version.sbt` instead of per-project files |

### Tagging settings

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoTagStrategy` | `MonorepoTagStrategy` | `PerProject` | `PerProject` or `Unified` |
| `releaseIOMonorepoTagName` | `(String, String) => String` | `(name, ver) => s"$name/v$ver"` | Per-project tag formatter |
| `releaseIOMonorepoUnifiedTagName` | `String => String` | `ver => s"v$ver"` | Unified tag formatter |

### Change detection settings

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoDetectChanges` | `Boolean` | `true` | Enable git-based change detection |
| `releaseIOMonorepoIncludeDownstream` | `Boolean` | `false` | Include transitive downstream dependents of changed projects |
| `releaseIOMonorepoChangeDetector` | `Option[(ProjectRef, File, State) => IO[Boolean]]` | `None` | Custom change detector |
| `releaseIOMonorepoDetectChangesExcludes` | `Seq[File]` | `Seq.empty` | Files to exclude from detection |
| `releaseIOMonorepoSharedPaths` | `Seq[String]` | `Seq("build.sbt", "project/")` | Root-level paths checked for shared changes per project |

Files matching `releaseIOMonorepoSharedPaths` (relative to the repo root) are checked against each project's last release tag. If any shared file changed since that tag, the project is marked as changed. This catches modifications to shared build definitions, compiler plugins, or dependency versions.

In per-project tag mode, each project is evaluated against its own tag independently. Set to `Seq.empty` to disable.

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

This releases `api` at version `1.0.0` regardless of whether change detection found changes in `api`. Force-included projects do not trigger downstream expansion — only projects detected as changed contribute to `releaseIOMonorepoIncludeDownstream` expansion.

### Custom change detector

```scala
releaseIOMonorepoChangeDetector := Some((ref: ProjectRef, baseDir: File, state: State) =>
  IO.pure(ref.project == "core") // only release "core"
)
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

### First release shows no projects changed

On a brand-new repo with no prior release tags, change detection marks all projects as changed — this is expected. If tags exist but under a different scheme, some projects may appear unchanged. Use `all-changed` to bypass detection, or disable it permanently with `releaseIOMonorepoDetectChanges := false`.

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

You can define your own `MonorepoStepIO` steps and add them to the release process alongside the built-in ones. Steps are either **Global** (run once) or **PerProject** (run once per selected project in topological order). Each step receives an immutable context (`MonorepoContext`) that it can read and transform.

### Plain steps

Use these factory methods in `build.sbt` or `project/*.scala`:

| Method | Scope | Execute returns |
|--------|-------|-----------------|
| `globalStep(name)(execute)` | Global | `IO[MonorepoContext]` |
| `perProjectStep(name, enableCrossBuild)(execute)` | PerProject | `IO[MonorepoContext]` |
| `globalStepAction(name)(execute)` | Global | `IO[Unit]` |
| `perProjectStepAction(name, enableCrossBuild)(execute)` | PerProject | `IO[Unit]` |

> `enableCrossBuild` is a named parameter that defaults to `false` — pass it by name to keep call
> sites self-documenting: `perProjectStep("name", enableCrossBuild = true) { ... }`.

```scala
import cats.effect.IO
import io.release.monorepo.MonorepoReleasePlugin.autoImport.*

// Global step — runs once, logs a release summary
val printSummary = globalStepAction("print-summary") { ctx =>
  IO.blocking {
    val names = ctx.currentProjects.map(_.name).mkString(", ")
    ctx.state.log.info(s"[release] Releasing projects: $names")
  }
}

// Per-project step — runs once per selected project
val logProject = perProjectStepAction("log-project")((ctx, project) =>
  IO.blocking(ctx.state.log.info(s"[release] Releasing ${project.name}"))
)

```

### Step context API

Every step receives a `MonorepoContext`. Per-project steps also receive a `ProjectReleaseInfo` for the current project.

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
| `failureCause` | `Option[Throwable]` | Throwable captured on failure |
| `withState(s)` | `MonorepoContext` | Replace sbt state |
| `withVcs(v)` | `MonorepoContext` | Set or replace VCS adapter |
| `withProjects(ps)` | `MonorepoContext` | Replace project list |
| `updateProject(ref)(f)` | `MonorepoContext` | Transform a single project's info |
| `metadata[A](key)` | `Option[A]` | Read typed inter-step metadata |
| `withMetadata[A](key, value)` | `MonorepoContext` | Store typed inter-step metadata |
| `withoutMetadata[A](key)` | `MonorepoContext` | Remove a metadata entry |
| `fail` | `MonorepoContext` | Mark release as failed |
| `failWith(cause)` | `MonorepoContext` | Mark release as failed with a cause |

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
| `failureCause` | `Option[Throwable]` | Throwable captured when this project's step fails |

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

### Builder API

For steps with validation, cross-build, or resource access, use the fluent builder API on `MonorepoStepIO`:

```scala
import cats.effect.IO
import io.release.monorepo.MonorepoStepIO

// Global validation-only step — fails the release if not on main
val checkBranch = MonorepoStepIO
  .global("check-branch")
  .withValidation(ctx =>
    ctx.vcs match {
      case Some(vcs) =>
        vcs.currentBranch.flatMap(branch =>
          if (branch == "main") IO.unit
          else IO.raiseError(new RuntimeException(s"Must release from main, not $branch"))
        )
      case None => IO.raiseError(new RuntimeException("VCS not initialized"))
    }
  )
  .validateOnly

// Per-project step with validation
val checkReadme = MonorepoStepIO
  .perProject("check-readme")
  .withValidation { (ctx, project) =>
    val readme = project.baseDir / "README.md"
    if (!readme.exists()) IO.raiseError(new RuntimeException(s"${project.name} missing README.md"))
    else IO.unit
  }
  .validateOnly

// Action step (IO[Unit] — context passed through unchanged)
val logStep = MonorepoStepIO
  .global("log")
  .executeAction(ctx => IO.blocking(ctx.state.log.info("[release] starting...")))
```

| Entry point | Builder | Terminal methods |
|-------------|---------|-----------------|
| `MonorepoStepIO.global(name)` | `GlobalBuilder` | `.execute(...)`, `.executeAction(...)`, `.validateOnly` |
| `MonorepoStepIO.perProject(name)` | `PerProjectBuilder` | `.execute(...)`, `.executeAction(...)`, `.validateOnly` |
| `MonorepoStepIO.globalResource[T](name)` | `ResourceGlobalBuilder[T]` | Same terminals, returns `T => MonorepoStepIO` |
| `MonorepoStepIO.perProjectResource[T](name)` | `ResourcePerProjectBuilder[T]` | Same terminals, returns `T => MonorepoStepIO` |

Optional builder methods: `.withValidation(...)`, `.withCrossBuild` (per-project only), `.withSelectionBoundary` (global only). Every builder chain ends with one of three terminal methods: `.execute(f)` runs `f` and returns the modified context, `.executeAction(f)` runs `f` for side effects and passes context through unchanged, `.validateOnly` creates a validation-only step with no execute logic. Resource-aware builders (`globalResource`, `perProjectResource`) are covered in [Custom plugins](#custom-plugins).

> **Selection boundaries**: A global step marked with `.withSelectionBoundary` splits the release into a setup segment and a main segment. Steps before the boundary run validate-then-execute sequentially; steps after it run all validations first, then all executions. The built-in `detect-or-select-projects` is the default boundary. Custom steps rarely need this.

### Customizing the release process

Filter, insert, or replace steps in `build.sbt`. Use `insertStepBefore` / `insertStepAfter` to add steps at specific positions by name (matching the `step.name` strings in the [default steps table](#default-release-steps)).

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

### Custom plugins

If your release process needs a shared resource (HTTP client, database connection, temporary directory), create a custom plugin that extends `MonorepoReleasePluginLike[T]`. The resource is acquired once before all steps run and released after they complete (or on failure).

Custom plugins must be defined in `project/*.scala` (not `build.sbt`) because sbt discovers `AutoPlugin` classes during meta-build compilation. You only need `enablePlugins(MyReleasePlugin)` — you do not need to enable `MonorepoReleasePlugin`.

> **Do not add `object autoImport`** to custom plugins. When both `MonorepoReleasePlugin` and a custom plugin define `autoImport`, the build gets ambiguous references (e.g. `reference to releaseIOMonorepoProcess is ambiguous`).

> **Important:** In `project/*.scala` files, `io.release` is shadowed by sbt's `sbt.io` package. Always use `_root_.io.release` and `_root_.cats.effect` imports.

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
      MonorepoStepIO
        .globalResource[HttpClient]("notify-slack")
        .executeAction(client => ctx =>
          IO.blocking(client.post("/webhook", "Released!"))
        )
}
```

Enable in `build.sbt` and run:

```scala
lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MyReleasePlugin)
```

```bash
sbt "myRelease with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"
```

#### Resource-aware builder API

Resource-aware steps use `MonorepoStepIO.globalResource[T]` and `MonorepoStepIO.perProjectResource[T]`. They produce `T => MonorepoStepIO` functions that receive the acquired resource:

```scala
private val metadataKey = AttributeKey[String]("release-metadata")

// Resource step that modifies the context — use .execute
val fetchMetadata: HttpClient => MonorepoStepIO = MonorepoStepIO
  .globalResource[HttpClient]("fetch-metadata")
  .execute(client => ctx =>
    IO.blocking(client.get("/release-metadata")).map(metadata =>
      ctx.withMetadata(metadataKey, metadata)
    )
  )
```

The plugin example above already shows `.executeAction` for side-effect-only steps.

Use `insertAfter` / `insertBefore` (shown in [Customizing the release process](#customizing-the-release-process)) to insert resource-aware steps at specific positions. Override `monorepoReleaseProcess` directly to build the step sequence from scratch — plain steps from `MonorepoReleaseSteps` and resource-aware steps can be mixed freely via implicit conversion.

> **Common pitfalls** with custom plugins:
> - `import sbt.*` shadows `io.release` — use `_root_` imports
> - Do not define `object autoImport` — it causes ambiguous references
> - Plugins must live in `project/*.scala`, not `build.sbt`

### Step timing

- The step list is frozen when the command starts.
- Built-in **Global** execute steps such as `resolve-release-order` and `detect-or-select-projects`
  read the current `State` when they run.
- Built-in **validate** functions after `detect-or-select-projects` run against the selected snapshot.
- Custom `PerProject` steps keep using `ctx.projects` until you replace that snapshot yourself.

Example: rewrite the project set via `State` so built-in steps see the change:

```scala
// Inside a MonorepoReleasePluginLike[Unit] plugin
private val selectOnlyCore = MonorepoStepIO
  .global("select-only-core")
  .execute(ctx =>
    IO.blocking {
      val extracted    = Project.extract(ctx.state)
      val root         = extracted.get(baseDirectory)
      val updatedState = extracted.appendWithSession(
        Seq(releaseIOMonorepoProjects := Seq(ProjectRef(root, "core"))),
        ctx.state
      )
      ctx.withState(updatedState)
    }
  )

override protected def monorepoReleaseProcess(state: State): Seq[Unit => MonorepoStepIO] =
  insertBefore(Project.extract(state).get(releaseIOMonorepoProcess), "resolve-release-order")(
    Seq((_: Unit) => selectOnlyCore)
  )
```

To filter the project set for later custom `PerProject` steps without touching `State`, use `ctx.withProjects(...)` instead.

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

# Undo commits (2 = commit-release-versions + commit-next-versions; use HEAD~1 if only one was made)
git reset --hard HEAD~2
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
- replace `resourceGlobalStep(...)`, `resourcePerProjectStep(...)`, and all `resource*` factory method variants with the `MonorepoStepIO` builder API (`MonorepoStepIO.globalResource[T](name)`, `MonorepoStepIO.perProjectResource[T](name)`)
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
