# Customization (core)

## Supported customization model

Core release customization has one surface, made of three knobs:

- `releaseIOPolicy*` turns built-in phases on or off
- `releaseIOHooks*` adds behavior around supported lifecycle points
- `ReleasePluginIOLike.releaseResourceHooks` lets custom plugins use one shared resource

## Choosing a hook factory

Every hook companion (`ReleaseHookIO`, `ReleaseResourceHookIO`) exposes three
intent-named factories backed by the same engine path. Pick the shortest form
that fits the hook you are writing:

| If your hook… | Use |
| ------------- | --- |
| fires a side effect (log, notify, audit) and leaves the context unchanged | `sideEffect` |
| computes a new `ReleaseContext` and returns it once | `transform` |
| mutates the context in multiple steps and needs every checkpoint visible to `afterFailure` | `resumable` |

The same hook written three ways:

```scala
import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

// 1. Fire and forget — context unchanged.
ReleaseHookIO.sideEffect("notify-tagged") { ctx =>
  IO.blocking(ctx.state.log.info(s"tagged ${ctx.releaseVersion.getOrElse("?")}"))
}

// 2. Replace the context once.
ReleaseHookIO.transform("skip-publish-for-snapshot") { ctx =>
  IO.pure(
    if (ctx.releaseVersion.exists(_.endsWith("SNAPSHOT"))) ctx.copy(skipPublish = true)
    else ctx
  )
}

// 3. Multiple checkpoints, each visible to afterFailure recovery.
ReleaseHookIO.resumable("stage-release") { handle =>
  handle.update(stageOne) *> handle.update(stageTwo)
}
```

Resource-aware hooks (`ReleaseResourceHookIO[T]`) add the resource `T` as the
first argument:

```scala
ReleaseResourceHookIO.sideEffect[HttpClient]("notify-api") { (client, ctx) =>
  IO.blocking(client.notifyRelease(ctx.releaseVersion.getOrElse("unknown")))
}
```

The legacy `.io` / `.action` / `.ioTracked` / `.actionTracked` constructors
still compile but are deprecated; prefer the three factories above.

## Hook-based customization

```scala
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

def markerHook(marker: String): ReleaseHookIO =
  ReleaseHookIO.sideEffect(marker) { ctx =>
    IO.blocking {
      val base = Project.extract(ctx.state).get(baseDirectory)
      sbt.IO.write(base / s"$marker.marker", marker + "\n")
    }
  }

releaseIOPolicyEnablePush := false
releaseIOPolicyEnablePublish := false
releaseIOHooksBeforeTag += markerHook("before-tag")
releaseIOHooksAfterTag += markerHook("after-tag")
```

Hook semantics:

- `beforeX` / `afterX` hooks run only when phase `X` is present in the compiled lifecycle
- disabled phases do not run their hooks
- `releaseIO check` validates the same lifecycle shape that `releaseIO` executes
- hooks extend behavior, but they do not change phase ordering

## Hook and policy recipes

These are common recipes, not the exhaustive hook/policy catalog; see
[Settings reference](reference.md) for the full key list.

| Intent | Setting |
| ------ | ------- |
| Remove `push-changes` | `releaseIOPolicyEnablePush := false` |
| Remove `publish-artifacts` | `releaseIOPolicyEnablePublish := false` |
| Remove `run-tests` or `run-clean` | `releaseIOPolicyEnableRunTests := false` / `releaseIOPolicyEnableRunClean := false` |
| Insert logic before tagging | `releaseIOHooksBeforeTag += ...` |
| Insert logic after tagging | `releaseIOHooksAfterTag += ...` |
| Insert logic around version resolution | `releaseIOHooksBeforeVersionResolution` / `releaseIOHooksAfterVersionResolution` |
| Insert logic around publish | `releaseIOHooksBeforePublish` / `releaseIOHooksAfterPublish` |

### Snippets

Disable push and publish:

```scala
releaseIOPolicyEnablePush := false
releaseIOPolicyEnablePublish := false
```

Run code before tagging:

```scala
import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

releaseIOHooksBeforeTag += ReleaseHookIO.sideEffect("write-release-marker") { ctx =>
  IO.blocking {
    val base = Project.extract(ctx.state).get(baseDirectory)
    sbt.IO.write(base / "release.marker", "before-tag\n")
  }
}
```

Add a notification after tagging:

```scala
import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

releaseIOHooksAfterTag += ReleaseHookIO.sideEffect("notify-tagged") { ctx =>
  IO.blocking {
    val version = ctx.releaseVersion.getOrElse("unknown")
    ctx.state.log.info(s"[release-io] Tagged $version")
  }
}
```

## Custom plugins with shared resources

Use `ReleasePluginIOLike[T]` when release behavior needs one shared resource, such as an
HTTP client. The supported extension point is `releaseResourceHooks`.

For `.scala` sources under `project/`, import grouped keys from the core plugin surface:

```scala
import io.release.ReleasePluginIO.autoImport.*
```

`ReleasePluginIO.autoImport.*` is the supported grouped-key surface for both core-only and shared
`releaseIO*` settings. Custom plugins that extend `ReleasePluginIOLike` inherit the same surface
through `MyReleasePlugin.autoImport.*`.

```scala
// project/MyReleasePlugin.scala
import sbt.*
import _root_.cats.effect.{IO, Resource}
import _root_.io.release.*

// Placeholder for your preferred HTTP client library (sttp, http4s, etc.).
// Swap this trait for the real type you use in your build.
trait HttpClient {
  def allowedBranches(): Set[String]
  def notifyRelease(version: String): Unit
  def close(): Unit
}

object MyReleasePlugin extends ReleasePluginIOLike[HttpClient] {
  override def trigger = noTrigger
  override protected def commandName = "releaseWithClient"

  override def resource: Resource[IO, HttpClient] =
    Resource.make(IO.blocking {
      new HttpClient {
        def allowedBranches(): Set[String]       = Set("main", "master")
        def notifyRelease(version: String): Unit = ()
        def close(): Unit                        = ()
      }
    })(client => IO.blocking(client.close()))

  private val validateBranch =
    ReleaseResourceHookIO.sideEffect[HttpClient]("validate-branch") { (client, ctx) =>
      IO.blocking(client.allowedBranches()).flatMap { allowed =>
        ctx.vcs match {
          case Some(vcs) =>
            vcs.currentBranch.flatMap { branch =>
              if (allowed(branch)) IO.unit
              else IO.raiseError(new RuntimeException(s"Branch '$branch' not allowed"))
            }
          case None =>
            IO.raiseError(new RuntimeException("VCS not initialized"))
        }
      }
    }

  private val notifyApi =
    ReleaseResourceHookIO.sideEffect[HttpClient]("notify-api") { (client, ctx) =>
      IO.blocking(client.notifyRelease(ctx.releaseVersion.getOrElse("unknown")))
    }

  override protected def releaseResourceHooks(state: State): ReleaseResourceHooks[HttpClient] =
    ReleaseResourceHooks(
      afterCleanCheckHooks = Seq(validateBranch),
      afterTagHooks = Seq(notifyApi)
    )
}
```

Notes:

- **Check mode and resources:** `check` runs every hook's `validate` function but never
  acquires the plugin resource. This is safe because `validate` on
  `ReleaseResourceHookIO` is always resource-free — it receives only the context, not
  the resource value. Hook authors should place any logic that depends on the resource
  (HTTP calls, temp-dir setup, etc.) in `execute`, not `validate`.
- `run` acquires the resource once via `Resource.use`, executes compiled hooks with the
  resource value, then releases it.
- `ReleasePluginIOLike` declares `autoImport` as `final`, so a custom plugin inherits the
  same grouped keys and cannot override them. In `.sbt` files, bare `releaseIO*` keys still work
  automatically when the plugin is enabled. In `.scala` sources under `project/`, import grouped
  keys from `ReleasePluginIO.autoImport.*` (or `MyReleasePlugin.autoImport.*`).
