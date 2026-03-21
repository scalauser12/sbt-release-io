# Custom steps and plugins (monorepo)

## Custom steps

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
> sites self-documenting: `perProjectStep("name", enableCrossBuild = true)(...)`.

```scala
import cats.effect.IO

// Global step — runs once, logs a release summary
val printSummary = globalStepAction("print-summary")(ctx =>
  IO.blocking {
    val names = ctx.currentProjects.map(_.name).mkString(", ")
    ctx.state.log.info(s"[release] Releasing projects: $names")
  }
)

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

The built-in monorepo flow also carries startup-only planning data internally (selection mode,
CLI version overrides, `with-defaults`, and the global-version write marker), but that runtime
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
val logPlannedVersions = globalStepAction("log-planned-versions")(ctx =>
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

val writeStep = globalStep("write-metadata")(ctx =>
  IO.pure(ctx.withMetadata(myKey, "hello"))
)

val readStep = globalStepAction("read-metadata")(ctx =>
  IO(ctx.state.log.info(ctx.metadata[String](myKey).getOrElse("(not set)")))
)
```

### Builder API

For steps with validation or cross-build, use the fluent builder API on `MonorepoStepIO`:

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

Optional builder methods: `.withValidation(...)`, `.withCrossBuild` (per-project only). Every builder chain ends with one of three terminal methods: `.execute(f)` runs `f` and returns the modified context, `.executeAction(f)` runs `f` for side effects and passes context through unchanged, `.validateOnly` creates a validation-only step with no execute logic. Resource-aware builders (`globalResource`, `perProjectResource`) are covered in [Custom plugins](#custom-plugins).

> **Selection boundary**: The built-in `detect-or-select-projects` step splits the release into a setup segment and a main segment. Steps before the boundary run validate-then-execute sequentially; steps after it run all validations first, then all executions.

### Customizing the release process

Filter, insert, or replace steps in `build.sbt`. Use `insertStepBefore` / `insertStepAfter` to add steps at specific positions by name (matching the `step.name` strings in the [default steps table](concepts.md#default-release-steps)).

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
