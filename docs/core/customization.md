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

The older flat names remain as deprecated aliases in this release. Prefer the grouped names in
`build.sbt`, even though `inspect` still prints the legacy sbt key labels.

## Key rename guide

| Old name | Preferred grouped name |
| -------- | ---------------------- |
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

- `check` validates resource-aware hooks without acquiring the resource
- `run` acquires the resource once, executes compiled hooks, then releases it
- custom plugins should not define `object autoImport`; reuse the keys from `ReleasePluginIO`

## Older API renames

When updating older builds or plugins:

- rename `step.check` to `step.validate`
- rename `step.action` to `step.execute`
- replace older convenience factories with `ReleaseHookIO`, `ReleaseResourceHookIO`, and
  the current `ReleaseStepIO` builder/factory APIs where needed internally

## Lower-level step types

`ReleaseStepIO` still exists as the lower-level step type used by built-ins, tests, and
internal composition. It is no longer the supported build-facing customization path for
changing the default release pipeline. Prefer hooks and policies in `build.sbt`.
