# Custom steps and plugins (core)

## Custom steps

Define your own steps using the `ReleaseStepIO.step(name)` builder and add them to the release process alongside the built-in ones (see [Resource-aware steps](#resource-aware-steps-builder-api) for the full builder method reference):

```scala
import cats.effect.IO
import io.release.{ReleaseContext, ReleaseStepIO}

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

Wrap existing sbt tasks, input tasks, or commands as release steps using the built-in factory methods:

```scala
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

## Custom plugins

If your release process needs a shared resource — an HTTP client, a database connection, a temporary directory — you can create a custom plugin that extends `ReleasePluginIOLike[T]`. The resource is acquired once before all steps run and released after they complete (or on failure), following the cats-effect `Resource` pattern.

### Creating the plugin

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

  // Resource-aware step using the builder API (see "Resource-aware steps")
  private val notifyApi = ReleaseStepIO
    .resourceStep[HttpClient]("notify-api")
    .executeAction(client => ctx =>
      IO.blocking(client.post("/releases", s"""{"version": "${ctx.releaseVersion.getOrElse("")}"}"""))
    )

  // Append the step after the defaults
  override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] =
    liftSteps(Project.extract(state).get(releaseIOProcess)) :+ notifyApi
}
```

### Configuring in build.sbt

Enable the plugin and configure the release process as usual:

```scala
// build.sbt
enablePlugins(MyReleasePlugin)

// All standard settings work — they come from the default ReleasePluginIO
releaseIOProcess := releaseIOProcess.value.filterNot(step =>
  step.name == "push-changes"
)

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

Use these in `releaseProcess`:

```scala
override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] =
  liftSteps(Project.extract(state).get(releaseIOProcess)) ++ Seq(notifySlack, startDeploy)
```

### Inserting steps at specific positions

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
