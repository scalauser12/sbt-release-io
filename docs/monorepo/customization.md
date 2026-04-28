# Customization (monorepo)

## Supported customization model

Monorepo customization has one surface, made of four knobs:

- `releaseIOMonorepoPolicy*` turns built-in phases on or off
- `releaseIOMonorepoHooks*` adds behavior around supported lifecycle points
- `MonorepoReleasePluginLike.monorepoResourceHooks` lets custom plugins use one shared resource
- protected behavior hooks on `MonorepoReleasePluginLike` let custom plugins override
  runtime defaults for cross-build, skip-tests, skip-publish, and interactive mode

## Choosing a hook factory

Every monorepo hook companion (`MonorepoGlobalHookIO`, `MonorepoProjectHookIO`,
and their resource-aware counterparts) exposes four intent-named factories
for these use cases. `sideEffect`, `transform`, and `resumable` use the tracked
execute path; `precondition` is validate-only and leaves `execute` as a no-op.
Pick the shortest form that fits:

| If your hook… | Use |
| ------------- | --- |
| fires a side effect (log, notify, audit) and leaves the context unchanged | `sideEffect` |
| computes a new `MonorepoContext` and returns it once | `transform` |
| mutates the context in multiple steps and needs each checkpoint preserved if a later step fails | `resumable` |
| guards the release (branch check, environment requirement, required files) and must be rehearsed by `releaseIOMonorepo check` | `precondition` |

`resumable` refers to recoverable context checkpoints; it does not make arbitrary
external side effects idempotent.

A global hook written four ways:

```scala
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO

MonorepoGlobalHookIO.sideEffect("print-selected") { ctx =>
  IO.println(s"selected: ${ctx.currentProjects.map(_.name).mkString(", ")}")
}

MonorepoGlobalHookIO.transform("drop-sandbox") { ctx =>
  IO.pure(ctx.withProjects(ctx.currentProjects.filter(_.name != "sandbox")))
}

MonorepoGlobalHookIO.resumable("stage") { handle =>
  handle.update(stageOne) *> handle.update(stageTwo)
}

// Guard hook — runs as `validate`, so `releaseIOMonorepo check` rehearses it.
MonorepoGlobalHookIO.precondition("require-readme") { ctx =>
  val base = Project.extract(ctx.state).get(baseDirectory)
  IO.blocking((base / "README.md").exists()).flatMap {
    case true  => IO.unit
    case false => IO.raiseError(new RuntimeException("README.md missing"))
  }
}
```

Per-project hooks (`MonorepoProjectHookIO`) take `(project, ctx)` /
`(project, handle)` so the code reads "for project X, do Y":

```scala
import _root_.io.release.monorepo.MonorepoProjectHookIO

MonorepoProjectHookIO.sideEffect("notify-tagged") { (project, _) =>
  IO.println(s"tagged ${project.name} as ${project.tagName.getOrElse("?")}")
}

MonorepoProjectHookIO.precondition("require-project-readme") { (project, _) =>
  IO.blocking((project.baseDir / "README.md").exists()).flatMap {
    case true  => IO.unit
    case false => IO.raiseError(new RuntimeException(s"${project.name}/README.md missing"))
  }
}
```

Resource-aware hooks (`MonorepoGlobalResourceHookIO[T]`,
`MonorepoProjectResourceHookIO[T]`) add the resource `T` as the first argument.

The legacy `.io` / `.action` constructors still compile but are deprecated;
prefer the four intent-named factories above. `.ioTracked` remains supported as
a lower-level escape hatch when you need direct `TrackedContextHandle` access.
`.actionTracked` is deprecated; use `resumable` instead.

> **Check-mode visibility.** `sideEffect`, `transform`, and `resumable` populate
> `execute` only; their `validate` is a no-op, so `releaseIOMonorepo check` does
> not rehearse them. Use `precondition` for guard hooks that must fail upfront,
> or set `validate` directly via the case-class constructor when a hook needs
> both a non-trivial `validate` and `execute`. A `precondition` is registered
> through a lifecycle hook slot, but its predicate runs during validation/check
> rather than during release execution.

## Hook-based customization

```scala
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO
import _root_.io.release.monorepo.MonorepoProjectHookIO

def markerHook(marker: String): MonorepoProjectHookIO =
  MonorepoProjectHookIO.sideEffect(marker) { (project, _) =>
    IO.blocking {
      sbt.IO.write(project.baseDir / s"$marker.marker", marker + "\n")
    }
  }

releaseIOMonorepoPolicyEnablePush := false
releaseIOMonorepoPolicyEnablePublish := false
releaseIOMonorepoHooksAfterCleanCheck +=
  MonorepoGlobalHookIO.sideEffect("announce-clean")(_ =>
    IO.println("[monorepo] clean working tree confirmed")
  )
releaseIOMonorepoHooksBeforeTag += markerHook("before-tag")
releaseIOMonorepoHooksAfterTag += markerHook("after-tag")
```

Hook semantics:

- `beforeX` / `afterX` hooks run only when phase `X` is present in the compiled lifecycle
- disabled phases do not run their hooks
- global lifecycle points use `MonorepoGlobalHookIO`
- per-project lifecycle points use `MonorepoProjectHookIO`
- `releaseIOMonorepo check` validates the same lifecycle shape that `releaseIOMonorepo`
  executes, but defers downstream selection/version-dependent validation when runtime hook
  state can still change those inputs

The tagging lifecycle phase is still named `tag-releases`. Customize around it with
`releaseIOMonorepoHooksBeforeTag` and `releaseIOMonorepoHooksAfterTag`.

## Hook and policy recipes

These are common recipes, not the exhaustive hook/policy catalog; see
[Settings reference](reference.md) for the full key list.

| Intent | Setting |
| ------ | ------- |
| Remove `push-changes` | `releaseIOMonorepoPolicyEnablePush := false` |
| Remove `publish-artifacts` | `releaseIOMonorepoPolicyEnablePublish := false` |
| Remove `run-clean` | `releaseIOMonorepoPolicyEnableRunClean := false` |
| Remove `run-tests` | `releaseIOMonorepoPolicyEnableRunTests := false` |
| Insert logic after clean working-dir validation | `releaseIOMonorepoHooksAfterCleanCheck` (global) |
| Insert logic around project selection | `releaseIOMonorepoHooksBeforeSelection` / `AfterSelection` (global) |
| Insert logic around version resolution | `releaseIOMonorepoHooksBeforeVersionResolution` / `AfterVersionResolution` (per-project) |
| Insert logic around release-version writes | `releaseIOMonorepoHooksBeforeReleaseVersionWrite` / `AfterReleaseVersionWrite` (per-project) |
| Insert logic around the release commit | `releaseIOMonorepoHooksBeforeReleaseCommit` / `AfterReleaseCommit` (global) |
| Insert logic around tagging | `releaseIOMonorepoHooksBeforeTag` / `AfterTag` (per-project) |
| Insert logic around publish | `releaseIOMonorepoHooksBeforePublish` / `AfterPublish` (per-project) |
| Insert logic around next-version writes | `releaseIOMonorepoHooksBeforeNextVersionWrite` / `AfterNextVersionWrite` (per-project) |
| Insert logic around the next-version commit | `releaseIOMonorepoHooksBeforeNextCommit` / `AfterNextCommit` (global) |
| Insert logic around push | `releaseIOMonorepoHooksBeforePush` / `AfterPush` (global) |

### Snippets

Disable push and publish:

```scala
releaseIOMonorepoPolicyEnablePush := false
releaseIOMonorepoPolicyEnablePublish := false
```

Run a global hook after the clean-working-dir check:

```scala
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO

releaseIOMonorepoHooksAfterCleanCheck +=
  MonorepoGlobalHookIO.sideEffect("announce-clean")(_ =>
    IO.println("[monorepo] clean working tree confirmed")
  )
```

Run a global hook after project selection:

```scala
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO

releaseIOMonorepoHooksAfterSelection +=
  MonorepoGlobalHookIO.sideEffect("print-selected-projects")(ctx =>
    IO.println(s"[monorepo] selected: ${ctx.currentProjects.map(_.name).mkString(", ")}")
  )
```

Add a per-project notification after tagging:

```scala
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

releaseIOMonorepoHooksAfterTag +=
  MonorepoProjectHookIO.sideEffect("notify-tagged") { (project, _) =>
    val tagName = project.tagName.getOrElse("unknown-tag")
    IO.println(s"[monorepo] tagged ${project.name} as $tagName")
  }
```

`afterTag` hooks are where `project.tagName` reflects the finalized tag name that was created.

## Custom plugins with shared resources

Use `MonorepoReleasePluginLike[T]` when release behavior needs one shared resource, such
as an HTTP client. The supported extension points are `monorepoResourceHooks` plus the
protected behavior hooks `crossBuildEnabled`, `skipTestsEnabled`, `skipPublishEnabled`,
and `interactiveEnabled`.

For new `.scala` sources, import monorepo-specific grouped keys from the monorepo plugin:

```scala
import _root_.io.release.monorepo.MonorepoReleasePlugin.autoImport.*
```

Shared/core `releaseIO*` keys are owned by the core plugin surface. When grouped keys are needed
from `.scala` sources, import them from
`ReleasePluginIO.autoImport.*`:

```scala
import _root_.io.release.ReleasePluginIO.autoImport.*
```

Because `MonorepoReleasePlugin` requires `ReleasePluginIO`, monorepo installs already bring the
shared/core `releaseIO*` settings surface along transitively.

When grouped keys are needed in custom Scala build code under `project/`, import
`releaseIOMonorepo*` keys from `MonorepoReleasePlugin.autoImport.*` and shared/core `releaseIO*`
keys from `ReleasePluginIO.autoImport.*`.

```scala
// project/MyMonorepoRelease.scala
import sbt.*
import _root_.cats.effect.{IO, Resource}
import _root_.io.release.monorepo.*

// Placeholder for your preferred HTTP client library (sttp, http4s, etc.).
// Swap this trait for the real type you use in your build.
trait HttpClient {
  def allowedProjects(): Set[String]
  def notifyTagged(project: String, tagName: String): Unit
  def close(): Unit
}

object MyMonorepoRelease extends MonorepoReleasePluginLike[HttpClient] {
  override def trigger = noTrigger
  override protected def commandName = "releaseMonorepoCustom"

  override def resource: Resource[IO, HttpClient] =
    Resource.make(IO.blocking {
      new HttpClient {
        def allowedProjects(): Set[String] = Set("core", "api")
        def notifyTagged(project: String, tagName: String): Unit = ()
        def close(): Unit = ()
      }
    })(client => IO.blocking(client.close()))

  override protected def crossBuildEnabled(state: State): Boolean = true

  private val validateProjects =
    MonorepoGlobalResourceHookIO.sideEffect[HttpClient]("validate-projects") { (client, ctx) =>
      IO.blocking(client.allowedProjects()).flatMap { allowed =>
        val invalid = ctx.currentProjects.map(_.name).filterNot(allowed.contains)
        if (invalid.nonEmpty)
          IO.raiseError(new RuntimeException(s"Projects not allowed: ${invalid.mkString(", ")}"))
        else IO.unit
      }
    }

  private val notifyTaggedProject =
    MonorepoProjectResourceHookIO.sideEffect[HttpClient]("notify-tagged-project") {
      (client, project, _) =>
        val tagName = project.tagName.getOrElse("unknown-tag")
        IO.blocking(client.notifyTagged(project.name, tagName))
    }

  override protected def monorepoResourceHooks(
      state: State
  ): MonorepoResourceHooks[HttpClient] =
    MonorepoResourceHooks(
      afterSelectionHooks = Seq(validateProjects),
      afterTagHooks = Seq(notifyTaggedProject)
    )
}
```

Notes:

- `check` never acquires the resource and validates only resource-aware hook phases whose
  validation context is stable without replaying earlier hook executes. For pure context
  guards (branch checks, required-file presence) use `precondition` so `check` rehearses
  them upfront; for guards that genuinely need the resource value, use `sideEffect` and
  accept that `check` cannot rehearse them
- `run` acquires the resource once, executes compiled hooks, then releases it
- protected behavior hooks default to the corresponding `releaseIOMonorepoBehavior*`
  settings and are intended for custom plugin authors, not ordinary `build.sbt`
  customization
- `MonorepoReleasePluginLike` declares `autoImport` as `final`, so a custom plugin
  inherits the same grouped keys and cannot override them. In `.sbt` files, monorepo builds get
  both `releaseIOMonorepo*` and the shared/core `releaseIO*` settings when the plugin is enabled.
  In `.scala` sources under `project/`, import monorepo-specific keys from
  `MonorepoReleasePlugin.autoImport.*` (or `MyMonorepoRelease.autoImport.*`) and shared/core
  settings from `ReleasePluginIO.autoImport.*`.
