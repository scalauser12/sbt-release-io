# Customization (core)

## Supported customization model

Core release customization is hook-first:

- `releaseIOPolicy*` turns built-in phases on or off
- `releaseIOHooks*` adds behavior around supported lifecycle points
- `ReleasePluginIOLike.releaseResourceHooks` lets custom plugins use one shared resource

Legacy raw-process step-list editing was removed. If you previously customized the release
by editing the step sequence directly, migrate to hooks and policies instead.

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

Prefer the grouped names in `build.sbt`; the breaking cleanup removed the older flat aliases and
renamed the underlying sbt key labels to match.

## Key rename guide

| Removed name | Replacement |
| ------------ | ----------- |
| `releaseIOEnablePush` | `releaseIOPolicyEnablePush` |
| `releaseIOEnablePublish` | `releaseIOPolicyEnablePublish` |
| `releaseIOEnableRunTests` | `releaseIOPolicyEnableRunTests` |
| `releaseIOBeforeTagHooks` | `releaseIOHooksBeforeTag` |
| `releaseIOAfterTagHooks` | `releaseIOHooksAfterTag` |
| `releaseIOBeforeVersionResolutionHooks` | `releaseIOHooksBeforeVersionResolution` |
| `releaseIOAfterVersionResolutionHooks` | `releaseIOHooksAfterVersionResolution` |
| `releaseIOBeforePublishHooks` | `releaseIOHooksBeforePublish` |
| `releaseIOAfterPublishHooks` | `releaseIOHooksAfterPublish` |

## Migration guide

| Old intent | New hook/policy surface |
| ---------- | ----------------------- |
| Remove `push-changes` | `releaseIOPolicyEnablePush := false` |
| Remove `publish-artifacts` | `releaseIOPolicyEnablePublish := false` |
| Remove `run-tests` or `run-clean` | `releaseIOPolicyEnableRunTests := false` / `releaseIOPolicyEnableRunClean := false` |
| Insert logic before tagging | `releaseIOHooksBeforeTag += ...` |
| Insert logic after tagging | `releaseIOHooksAfterTag += ...` |
| Insert logic around version resolution | `releaseIOHooksBeforeVersionResolution` / `releaseIOHooksAfterVersionResolution` |
| Insert logic around publish | `releaseIOHooksBeforePublish` / `releaseIOHooksAfterPublish` |

### Copy/paste replacements

Disable push and publish:

```scala
releaseIOPolicyEnablePush := false
releaseIOPolicyEnablePublish := false
```

Replace a "run this before tagging" custom step with a lifecycle hook:

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
- custom plugins should not define `object autoImport`; reuse the keys from `ReleasePluginIO`

## Older API renames

When updating older builds or plugins:

- replace flat `releaseIO*` key names with grouped `releaseIOBehavior*`, `releaseIODefaults*`,
  `releaseIOPolicy*`, `releaseIOHooks*`, `releaseIOVersioning*`, `releaseIOVcs*`,
  `releaseIOPublish*`, `releaseIORuntime*`, and `releaseIODiagnostics*`
- replace older low-level step-list edits with `ReleaseHookIO`, `ReleaseResourceHookIO`,
  grouped hook settings, and grouped policy settings

The low-level `ReleaseStepIO` DSL was removed in the breaking API cleanup. Build-facing
customization now goes through hooks, resource hooks, and grouped hook/policy settings only.
