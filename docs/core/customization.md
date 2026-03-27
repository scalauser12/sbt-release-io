# Custom steps and plugins (core)

## Hook-based customization

The default `ReleasePluginIO` now supports semantic lifecycle hooks and phase policies
without requiring raw `releaseIOProcess` surgery. When the built-in process is left
intact, the plugin compiles `releaseIOEnable*` and `releaseIO*Hooks` settings into the
internal engine so `releaseIO` and `releaseIO check` stay aligned.

```scala
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

def markerHook(marker: String): ReleaseHookIO =
  ReleaseHookIO.action(marker) { ctx =>
    IO.blocking {
      val base = Project.extract(ctx.state).get(baseDirectory)
      sbt.IO.write(base / s"$marker.marker", marker + "\n")
    }
  }

releaseIOEnablePush := false
releaseIOBeforeTagHooks += markerHook("before-tag")
releaseIOAfterTagHooks += markerHook("after-tag")
releaseIOEnablePublish := false
```

Hook semantics:

- `beforeX` / `afterX` hooks run only when phase `X` actually runs
- Disabled or skipped phases do not fire their normal hooks
- Hooks extend release behavior, but they do not control phase ordering or batching
- `releaseIO check` validates the same compiled lifecycle shape that `releaseIO` executes

### Legacy raw-process mode

`releaseIOProcess`, `ReleasePluginIOLike.releaseProcess`, and
`ReleasePluginIOLike.releaseCheckProcess` remain supported during the migration window,
but they are now the legacy raw-process API. When any of those are customized, the plugin
stays in legacy mode and ignores the hook/policy settings above.

### Migration from raw-process customization

For the common customization cases, prefer the semantic hook/policy settings over direct
`releaseIOProcess` surgery:

| Legacy pattern | Hook / policy replacement |
| -------------- | ------------------------- |
| Remove `push-changes` | `releaseIOEnablePush := false` |
| Remove `publish-artifacts` | `releaseIOEnablePublish := false` |
| Remove `run-tests` / `run-clean` | `releaseIOEnableRunTests := false` / `releaseIOEnableRunClean := false` |
| Insert logic before or after a built-in phase | `releaseIOBefore*Hooks` / `releaseIOAfter*Hooks` |
| Keep the built-in process but add extra behavior | Hook settings |
| Replace the full step order or use resource-backed custom plugin wiring | Legacy raw-process mode |

### Copy/paste replacements

Disable push and publish without touching the step list:

```scala
releaseIOEnablePush := false
releaseIOEnablePublish := false
```

Replace “insert a marker step before tagging” with a lifecycle hook:

```scala
import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

releaseIOBeforeTagHooks += ReleaseHookIO.action("write-release-marker") { ctx =>
  IO.blocking {
    val base = Project.extract(ctx.state).get(baseDirectory)
    sbt.IO.write(base / "release.marker", "before-tag\n")
  }
}
```

Keep the built-in process and add a notification after tagging:

```scala
import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

releaseIOAfterTagHooks += ReleaseHookIO.action("notify-tagged") { ctx =>
  IO.blocking {
    val version = ctx.releaseVersion.getOrElse("unknown")
    ctx.state.log.info(s"[release-io] Tagged $version")
  }
}
```

### Migrating older custom step APIs

If you are updating a custom plugin or build from an older release:

- rename `step.check` to `step.validate`
- rename `step.action` to `step.execute`
- replace `stepTask(...)`, `stepTaskAggregated(...)`, `stepInputTask(...)`, `stepCommand(...)`,
  and `stepCommandAndRemaining(...)` from `ReleaseIO` with the canonical
  `ReleaseStepIO.fromTask(...)`, `fromTaskAggregated(...)`, `fromInputTask(...)`,
  `fromCommand(...)`, and `fromCommandAndRemaining(...)` APIs
- replace `resourceStep(...)`, `resourceStepAction(...)`, `resourceStepWithCheck(...)`,
  `resourceStepWithValidation(...)`, and `resourceStepActionWithValidation(...)` factory methods
  with the `ReleaseStepIO.resourceStep[T](name)` builder API
- replace string attributes with typed metadata via `ctx.withMetadata`, `ctx.metadata`, and
  `AttributeKey[A]`

## Custom steps

Define your own steps using the `ReleaseStepIO.step(name)` builder and add them to the release process alongside the built-in ones (see [Resource-aware steps](#resource-aware-steps-builder-api) for the full builder method reference):

```scala
import _root_.cats.effect.IO
import _root_.io.release.{ReleaseContext, ReleaseStepIO}

// Log the planned release version
val printVersion = ReleaseStepIO.step("print-version")
  .executeAction(ctx =>
    IO.blocking(ctx.state.log.info(s"[release] Releasing ${ctx.releaseVersion.getOrElse("unknown")}"))
  )

// Validation-only step — fails if not on main
val checkBranch = ReleaseStepIO.step("check-branch")
  .withValidation(ctx =>
    ctx.vcs match {
      case Some(vcs) =>
        vcs.currentBranch.flatMap(branch =>
          if (branch == "main") IO.unit
          else IO.raiseError(new RuntimeException(s"Must release from main, not $branch"))
        )
      case None =>
        IO.raiseError(new RuntimeException("VCS not initialized"))
    }
  )
  .validateOnly
```

**`ReleaseContext`** — immutable context threaded through all steps:

| Field / Method                              | Type                       | Description                                                |
| ------------------------------------------- | -------------------------- | ---------------------------------------------------------- |
| `state`                                     | `State`                    | Current sbt state                                          |
| `versions`                                  | `Option[(String, String)]` | `(releaseVersion, nextVersion)`, set by `inquire-versions` |
| `releaseVersion`                            | `Option[String]`           | Shorthand for `versions.map(_._1)`                         |
| `nextVersion`                               | `Option[String]`           | Shorthand for `versions.map(_._2)`                         |
| `vcs`                                       | `Option[Vcs]`              | Git adapter, set by `initialize-vcs`                       |
| `skipTests` / `skipPublish` / `interactive` | `Boolean`                  | Execution flags                                            |
| `failed`                                    | `Boolean`                  | Whether the release has failed                             |
| `failureCause`                              | `Option[Throwable]`        | Throwable captured on failure                              |
| `withState(s)`                              | `ReleaseContext`           | Replace sbt state                                          |
| `withVersions(release, next)`               | `ReleaseContext`           | Set version pair                                           |
| `withVcs(v)`                                | `ReleaseContext`           | Set or replace VCS adapter                                 |
| `metadata[A](key)`                          | `Option[A]`                | Read typed inter-step metadata                             |
| `withMetadata[A](key, value)`               | `ReleaseContext`           | Store typed inter-step metadata                            |
| `withoutMetadata[A](key)`                   | `ReleaseContext`           | Remove a metadata entry                                    |
| `fail`                                      | `ReleaseContext`           | Mark release as failed                                     |
| `failWith(cause)`                           | `ReleaseContext`           | Mark release as failed with a cause                        |

The built-in implementation also threads startup-only command data internally (for example,
`with-defaults` and CLI version overrides), but that runtime metadata is package-private.
For custom steps, treat `ctx.withMetadata` / `ctx.metadata` as your extension space. The only
intentional mirror onto `sbt.State` is the release version pair, which sbt tasks use to
compute live tag names and commit messages.

### Creating steps from sbt tasks and commands

Wrap existing sbt tasks, input tasks, or commands as release steps by calling the
canonical `ReleaseStepIO` factory methods directly:

```scala
releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  ReleaseSteps.checkCleanWorkingDir,
  ReleaseSteps.inquireVersions,
  // Run a TaskKey as a release step
  ReleaseStepIO.fromTask(myCustomTask),
  // Run a TaskKey with cross-building enabled
  ReleaseStepIO.fromTask(myCustomTask, enableCrossBuild = true),
  // Run an InputKey with arguments
  ReleaseStepIO.fromInputTask(myInputTask, args = "arg1 arg2"),
  // Run a TaskKey aggregated across subprojects
  ReleaseStepIO.fromTaskAggregated(test),
  // Run an sbt command string
  ReleaseStepIO.fromCommand("publishLocal"),
  // Run a command that enqueues sub-commands (e.g. +publish)
  ReleaseStepIO.fromCommandAndRemaining("+publish"),
  ReleaseSteps.pushChanges
)
```

The supported step-construction surface is `ReleaseStepIO`: use `step(...)` /
`resourceStep(...)` for builder-style definitions, `fromTask` / `fromInputTask` /
`fromTaskAggregated` / `fromCommand` / `fromCommandAndRemaining` for wrapping existing sbt
entry points, and `pure` for non-effectful context transformations.

## Custom plugins

If your release process needs a shared resource — an HTTP client, a database connection, a temporary directory — you can create a custom plugin that extends `ReleasePluginIOLike[T]`. The resource is acquired once before all steps run and released after they complete (or on failure), following the cats-effect `Resource` pattern.

Custom plugins still participate in the compiled hook/policy flow by default. Legacy raw-process
mode starts only when the plugin materially changes the effective process, for example by
returning extra steps from `releaseProcess` or `releaseCheckProcess`. Merely defining a custom
plugin or overriding unrelated members such as `commandName` or `resource` does not bypass hooks.

### Creating the plugin

Custom plugins must be defined in `project/*.scala` (not `build.sbt`) because sbt discovers `AutoPlugin` classes during meta-build compilation.

> **Important:** In `project/*.scala` files, `io.release` is shadowed by sbt's `sbt.io` package. Always use `_root_.io.release` and `_root_.cats.effect` imports.

Here is a plugin that manages an HTTP client and keeps that custom behavior on compiled hook mode:

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

  private val validateBranch = ReleaseResourceHookIO.io[HttpClient]("validate-branch")(client =>
    ctx =>
      IO.blocking(client.get("/allowed-branches").split(",").toSet).flatMap(allowed =>
        ctx.vcs match {
          case Some(vcs) =>
            vcs.currentBranch.flatMap(branch =>
              if (allowed(branch)) IO.pure(ctx)
              else IO.raiseError(new RuntimeException(s"Branch '$branch' not allowed"))
            )
          case None      => IO.raiseError(new RuntimeException("VCS not initialized"))
        }
      )
  )

  private val notifyApi = ReleaseResourceHookIO.action[HttpClient]("notify-api")(client =>
    ctx =>
      IO.blocking(
        client.post("/releases", s"""{"version": "${ctx.releaseVersion.getOrElse("")}"}""")
      )
  )

  override protected def releaseResourceHooks(
      state: State
  ): ReleaseResourceHooks[HttpClient] =
    ReleaseResourceHooks(
      beforeVersionResolutionHooks = Seq(validateBranch),
      afterTagHooks = Seq(notifyApi)
    )
}
```

`releaseWithClient check ...` still stays resource-free. Resource-aware hooks contribute their
`validate` function during `check`, but the plugin resource is acquired only for the real release
run, where both validation and execute logic are active.

If a supported lifecycle point is enough, prefer `releaseResourceHooks` over direct
`releaseProcess` editing. Override `releaseProcess` / `releaseCheckProcess` only when you truly
need custom step ordering or a fully custom pipeline; that path still uses legacy raw-process
mode.

> **Tag preflight and custom version resolution:** `check` preflights tag availability only when `inquire-versions` is in the configured process. If you replace `inquire-versions` with custom version resolution, `check` reports tag status as "not evaluated (tags depend on runtime/custom version setup)" because it cannot compute the tag name without the built-in version step. The real release will still create tags normally.

### Configuring in build.sbt

Enable the plugin and configure the release process as usual:

```scala
// build.sbt
enablePlugins(MyReleasePlugin)

// Keep the default process and configure it semantically
releaseIOEnablePush := false

releaseIOCrossBuild := true
```

Run the release with your custom command name:

```bash
sbt "releaseWithClient with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

The command accepts the same arguments as `releaseIO` (`with-defaults`, `skip-tests`, `cross`, `release-version`, `next-version`, `default-tag-exists-answer`).

### Resource-aware steps (builder API)

Use the `ReleaseStepIO.resourceStep[T]` builder to create steps that receive an acquired resource:

```scala
// Simple resource step
val notifySlack: HttpClient => ReleaseStepIO = ReleaseStepIO
  .resourceStep[HttpClient]("notify-slack")
  .executeAction(client => ctx =>
    IO.blocking(client.post("/webhook", s"Released ${ctx.releaseVersion.getOrElse("")}"))
  )

// Resource step that modifies the context (stores a deploy URL for later steps)
val DeployUrl = AttributeKey[String]("deploy-url")

val startDeploy: HttpClient => ReleaseStepIO = ReleaseStepIO
  .resourceStep[HttpClient]("start-deploy")
  .execute(client => ctx =>
    IO.blocking {
      val url = client.post("/deploys", ctx.releaseVersion.getOrElse(""))
      ctx.withMetadata(DeployUrl, url)
    }
  )

// Validation-only resource step (checks connectivity, no execute logic)
val checkApi: HttpClient => ReleaseStepIO = ReleaseStepIO
  .resourceStep[HttpClient]("check-api")
  .withValidation(client => _ => IO.blocking(client.get("/health")).void)
  .validateOnly
```

Builder methods: `.withValidation(...)`, `.withCrossBuild`. Every chain ends with a terminal: `.execute(f)` runs `f` and returns the modified context, `.executeAction(f)` runs `f` for side effects and passes context through unchanged, `.validateOnly` creates a validation-only step with no execute logic.

Use these in `releaseProcess` when you need legacy raw-process wiring that the lifecycle hooks
cannot express:

```scala
override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] =
  liftSteps(Project.extract(state).get(releaseIOProcess)) ++ Seq(notifySlack, startDeploy)
```

### Inserting steps at specific positions (legacy raw-process mode)

`liftSteps` lifts plain `ReleaseStepIO` steps into resource-compatible `T => ReleaseStepIO` functions; `++` appends the resource steps. To insert at a specific position, use `insertAfter` or `insertBefore`:

```scala
override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] =
  insertAfter(Project.extract(state).get(releaseIOProcess), "check-clean-working-dir")(
    Seq(notifySlack)
  )
```

`insertAfter` and `insertBefore` match the exact `step.name` strings shown in the [default steps table](getting-started.md#default-release-steps).

### Fully custom release process

Override `releaseProcess` directly to build the step sequence from scratch instead of
appending to the defaults. Plain steps (from `ReleaseSteps`) and resource-aware steps
can be mixed freely — an implicit conversion lifts plain steps into resource-ignoring functions.

This is legacy raw-process mode. Prefer the hook/policy API above when it can express the
desired behavior, and keep direct process construction for advanced cases that truly need
step-level control.

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

  // --- resource-aware steps using the builder API ---

  private val validateBranch: HttpClient => ReleaseStepIO =
    ReleaseStepIO.resourceStep[HttpClient]("validate-branch")
      .withValidation(client => ctx =>
        IO.blocking(client.get("/allowed-branches")).flatMap(branches =>
          ctx.vcs match {
            case Some(vcs) =>
              vcs.currentBranch.flatMap(branch =>
                if (branches.contains(branch)) IO.unit
                else IO.raiseError(new RuntimeException(s"Branch '$branch' not allowed"))
              )
            case None => IO.raiseError(new RuntimeException("VCS not initialized"))
          }
        )
      )
      .validateOnly

  private val notifySlack: HttpClient => ReleaseStepIO =
    ReleaseStepIO.resourceStep[HttpClient]("notify-slack")
      .executeAction(client => ctx =>
        IO.blocking(client.post("/webhook", s"Tagged ${ctx.releaseVersion.getOrElse("")}"))
      )

  private val verifyPublish: HttpClient => ReleaseStepIO =
    ReleaseStepIO.resourceStep[HttpClient]("verify-publish")
      .executeAction(client => ctx =>
        IO.blocking(client.get(s"/artifacts/${ctx.releaseVersion.getOrElse("")}")).void
      )

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

### Key design points

| Concern                            | Approach                                                                                                                                                        |
| ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Coexisting with default plugin** | Use `trigger = noTrigger` + `enablePlugins(...)` in `build.sbt`, and override `commandName` to avoid duplicate command registration                             |
| **Adding resource steps**          | Override `releaseProcess` using `liftSteps` (append), `insertAfter`/`insertBefore` (positional insert)                                                          |
| **Setting keys**                   | All `releaseIO`* setting keys are singletons — they work regardless of which plugin exports them                                                                |
| **Do not add autoImport**          | Do not define `object autoImport` in custom plugins — it causes ambiguous references with `ReleasePluginIO` (e.g. `reference to releaseIOProcess is ambiguous`) |
