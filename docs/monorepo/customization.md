# Custom steps and plugins (monorepo)

## Hook-based customization

The default `MonorepoReleasePlugin` now supports semantic lifecycle hooks and phase
policies without requiring raw `releaseIOMonorepoProcess` surgery. When the built-in
process is left intact, the plugin compiles `releaseIOMonorepoEnable*` and
`releaseIOMonorepo*Hooks` settings into the internal engine so `releaseIOMonorepo`
and `releaseIOMonorepo check` stay aligned.

```scala
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

def markerHook(marker: String): MonorepoProjectHookIO =
  MonorepoProjectHookIO.action(marker) { (_, project) =>
    IO.blocking {
      sbt.IO.write(project.baseDir / s"$marker.marker", marker + "\n")
    }
  }

releaseIOMonorepoEnablePush      := false
releaseIOMonorepoBeforeTagHooks  += markerHook("before-tag")
releaseIOMonorepoAfterTagHooks   += markerHook("after-tag")
releaseIOMonorepoEnablePublish   := false
```

Hook semantics:

- `beforeX` / `afterX` hooks run only when phase `X` is present in the compiled process
- Disabled or skipped phases do not fire their normal hooks
- Hooks extend release behavior, but they do not control phase ordering or batching
- `releaseIOMonorepo check` validates the same compiled lifecycle shape that `releaseIOMonorepo` executes

Global lifecycle points use `MonorepoGlobalHookIO`; per-project lifecycle points use
`MonorepoProjectHookIO`.

### Legacy raw-process mode

`releaseIOMonorepoProcess`, `MonorepoReleasePluginLike.monorepoReleaseProcess`, and
`MonorepoReleasePluginLike.monorepoReleaseCheckProcess` remain supported during the
migration window, but they are now the legacy raw-process API. When any of those are
customized, the plugin stays in legacy mode and ignores the hook/policy settings above.

### Migration from raw-process customization

For the common customization cases, prefer the semantic hook/policy settings over direct
`releaseIOMonorepoProcess` surgery:

| Legacy pattern | Hook / policy replacement |
| -------------- | ------------------------- |
| Remove `push-changes` | `releaseIOMonorepoEnablePush := false` |
| Remove `publish-artifacts` | `releaseIOMonorepoEnablePublish := false` |
| Remove `run-clean` | `releaseIOMonorepoEnableRunClean := false` |
| Remove `run-tests` | `releaseIOMonorepoEnableRunTests := false` |
| Insert logic before or after project selection | `releaseIOMonorepoBeforeSelectionHooks` / `releaseIOMonorepoAfterSelectionHooks` |
| Insert logic before or after per-project phases | `releaseIOMonorepoBefore*Hooks` / `releaseIOMonorepoAfter*Hooks` |
| Keep the built-in process but add extra behavior | Hook settings |
| Replace the full step order or use resource-backed custom plugin wiring | Legacy raw-process mode |

## Custom steps

You can define your own `MonorepoStepIO` steps and add them to the release process alongside the built-in ones. Steps are either **Global** (run once) or **PerProject** (run once per selected project in topological order). Each step receives an immutable context (`MonorepoContext`) that it can read and transform.

### Plain steps

Construct steps directly through the canonical `MonorepoStepIO` builders in `build.sbt`
or `project/*.scala`:

```scala
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoStepIO

// Global step — runs once, logs a release summary
val printSummary = MonorepoStepIO
  .global("print-summary")
  .executeAction(ctx =>
    IO.println(
      s"[release] Releasing projects: ${ctx.currentProjects.map(_.name).mkString(", ")}"
    )
  )

// Per-project step — runs once per selected project
val logProject = MonorepoStepIO
  .perProject("log-project")
  .executeAction((ctx, project) =>
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

The built-in monorepo flow also carries startup-only planning data internally (selection mode,
CLI version overrides, and `with-defaults`), but that runtime
metadata stays package-private. Custom steps should use `ctx.withMetadata` / `ctx.metadata`
for their own shared data and should treat `metadataBag` as extension space, not as the main
built-in plan channel.

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

Use `ctx.updateProject(ref)(_.copy(...))` to update a single project's fields from within a step — for example, `ctx.updateProject(project.ref)(_.copy(tagName = Some("custom-tag")))`.
This is the per-project complement to `ctx.withMetadata` / `ctx.metadata`, which store global (non-project-scoped) values.

```scala
// Global step: after inquire-versions has run, log every project's planned release version
val logPlannedVersions = MonorepoStepIO
  .global("log-planned-versions")
  .executeAction(ctx =>
    IO {
      ctx.currentProjects.foreach(p =>
        p.releaseVersion match {
          case Some(v) => ctx.state.log.info(s"[release] ${p.name} → $v")
          case None    => ctx.state.log.warn(s"[release] ${p.name} — no release version set yet")
        }
      )
    }
  )
```

For global (non-project-scoped) data shared across steps, use typed metadata:

```scala
private val myKey = AttributeKey[String]("myKey")

val writeStep = MonorepoStepIO
  .global("write-metadata")
  .execute(ctx => IO.pure(ctx.withMetadata(myKey, "hello")))

val readStep = MonorepoStepIO
  .global("read-metadata")
  .executeAction(ctx =>
    IO(ctx.state.log.info(ctx.metadata[String](myKey).getOrElse("(not set)")))
  )
```

### Builder API

All step construction goes through the fluent builder API on `MonorepoStepIO`. For
validation or cross-build, chain the corresponding builder methods:

```scala
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoStepIO

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

Optional builder methods: `.withValidation(...)`, `.withCrossBuild` (per-project only). Every builder chain ends with one of three terminal methods: `.execute(f)` runs `f` and returns the modified context, `.executeAction(f)` runs `f` for side effects and passes context through unchanged, `.validateOnly` creates a validation-only step with no execute logic. Resource-aware builders (`globalResource`, `perProjectResource`) are covered in [Custom plugins](#custom-plugins).

> **Selection boundary**: The built-in `detect-or-select-projects` step splits the release into a setup segment and a main segment. Steps before the boundary run validate-then-execute sequentially; steps after it run all validations first, then all executions.

### Customizing the release process (legacy raw-process mode)

Filter, insert, or replace steps in `build.sbt`. Use `insertStepBefore` / `insertStepAfter` to add steps at specific positions by name (matching the `step.name` strings in the [default steps table](concepts.md#default-release-steps)).

```scala
// Filter out steps
releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot(_.name == "push-changes")

// Add a step at a specific position
releaseIOMonorepoProcess := insertStepBefore(releaseIOMonorepoProcess.value, "tag-releases")(
  Seq(checkBranch)
)

// Replace the entire process (step vals from MonorepoReleaseSteps)
import _root_.io.release.monorepo.steps.MonorepoReleaseSteps.*

releaseIOMonorepoProcess := Seq(
  initializeVcs, checkCleanWorkingDir, resolveReleaseOrder,
  detectOrSelectProjects, checkSnapshotDependencies, inquireVersions,
  // runClean, runTests, and publishArtifacts omitted — tag-only release
  setReleaseVersions, commitReleaseVersions, tagReleases,
  setNextVersions, commitNextVersions, pushChanges
)
```

### Custom plugins

If your release process needs a shared resource (HTTP client, database connection, temporary directory), create a custom plugin that extends `MonorepoReleasePluginLike[T]`. The resource is acquired once before all steps run and released after they complete (or on failure).

Custom plugins still participate in the compiled hook/policy flow by default. Legacy raw-process
mode starts only when the plugin materially changes the effective process, for example by
returning extra steps from `monorepoReleaseProcess` or `monorepoReleaseCheckProcess`. Merely
defining a custom plugin or overriding unrelated members such as `commandName` or `resource` does
not bypass hooks.

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

Because this example changes the effective release process, it intentionally uses legacy
raw-process mode for that custom command.

`myRelease check ...` does **not** acquire `resource` by default. `check` reads the plain
configured `releaseIOMonorepoProcess` through the protected
`monorepoReleaseCheckProcess(state)` hook, so normal release behavior stays unchanged while
preflight avoids custom resource acquisition.

If a custom plugin only changes `monorepoReleaseProcess`, the real release run switches to legacy
raw-process mode after the resource-backed steps are materialized. `check` stays on the plain
configured process until `monorepoReleaseCheckProcess` is also customized.

If you want custom monorepo steps to participate in `check`, override that hook with
resource-free preflight equivalents:

```scala
override protected def monorepoReleaseCheckProcess(state: State): Seq[MonorepoStepIO] =
  Project.extract(state).get(releaseIOMonorepoProcess) :+
    MonorepoStepIO
      .global("notify-slack-preflight")
      .withValidation(ctx =>
        IO.blocking(ctx.state.log.info("Would notify Slack during the real release"))
      )
      .validateOnly
```

That hook is the intended legacy/custom-plugin customization point for `check`. It lets custom
plugins keep preflight coverage for resource-backed release logic without acquiring the main
release resource.

> **Tag preflight and custom version resolution:** `check` preflights tag availability only when `inquire-versions` is in the configured process. If you replace `inquire-versions` with custom version resolution, `check` reports tag status as "not evaluated (tags depend on runtime/custom version setup)" because it cannot compute the tag name without the built-in version step. The real release will still create tags normally.

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

In `build.sbt`, use `insertStepAfter` / `insertStepBefore` to position plain steps. Inside `MonorepoReleasePluginLike` subclasses, use the protected `insertAfter` / `insertBefore` helpers when inserting resource-aware steps into `monorepoReleaseProcess`. Plain steps from `MonorepoReleaseSteps` and resource-aware steps can still be mixed freely via implicit conversion.

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
// Inside a MonorepoReleasePluginLike[Unit] plugin (use Unit when no shared resource is needed)
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
