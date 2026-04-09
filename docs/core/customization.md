# Customization (core)

## Supported customization model

Core release customization has one surface, made of three knobs:

- `releaseIOPolicy*` turns built-in phases on or off
- `releaseIOHooks*` adds behavior around supported lifecycle points
- `ReleasePluginIOLike.releaseResourceHooks` lets custom plugins use one shared resource

## Hook-based customization

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

releaseIOHooksBeforeTag += ReleaseHookIO.action("write-release-marker") { ctx =>
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

releaseIOHooksAfterTag += ReleaseHookIO.action("notify-tagged") { ctx =>
  IO.blocking {
    val version = ctx.releaseVersion.getOrElse("unknown")
    ctx.state.log.info(s"[release-io] Tagged $version")
  }
}
```

## Custom plugins with shared resources

Use `ReleasePluginIOLike[T]` when release behavior needs one shared resource, such as an
HTTP client. The supported extension point is `releaseResourceHooks`.

For new `.scala` sources, prefer the plugin auto-import explicitly:

```scala
import io.release.ReleasePluginIO.autoImport.*
```

When grouped keys are needed in custom Scala build code under `project/`, import them from
`ReleasePluginIO.autoImport.*` or qualify them through `ReleasePluginIO.autoImport`.

```scala
// project/MyReleasePlugin.scala
import sbt.*
import _root_.cats.effect.{IO, Resource}
import _root_.io.release.*

object MyReleasePlugin extends ReleasePluginIOLike[HttpClient] {
  override def trigger = noTrigger
  override protected def commandName = "releaseWithClient"

  override def resource: Resource[IO, HttpClient] =
    Resource.make(IO.blocking(new HttpClient("https://api.example.com")))(client =>
      IO.blocking(client.close())
    )

  private val validateBranch =
    ReleaseResourceHookIO.io[HttpClient]("validate-branch")(client => ctx =>
      IO.blocking(client.allowedBranches()).flatMap { allowed =>
        ctx.vcs match {
          case Some(vcs) =>
            vcs.currentBranch.flatMap { branch =>
              if (allowed(branch)) IO.pure(ctx)
              else IO.raiseError(new RuntimeException(s"Branch '$branch' not allowed"))
            }
          case None =>
            IO.raiseError(new RuntimeException("VCS not initialized"))
        }
      }
    )

  private val notifyApi =
    ReleaseResourceHookIO.action[HttpClient]("notify-api")(client => ctx =>
      IO.blocking(client.notifyRelease(ctx.releaseVersion.getOrElse("unknown")))
    )

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
- custom plugins already inherit `autoImport`, but grouped keys referenced from `.scala`
  sources should use `ReleasePluginIO.autoImport`
- do not add your own `object autoImport` unless you intentionally want a different public surface
