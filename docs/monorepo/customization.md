# Customization (monorepo)

## Supported customization model

Monorepo customization is hook-first:

- `releaseIOMonorepoEnable*` turns built-in phases on or off
- `releaseIOMonorepo*Hooks` adds behavior around supported lifecycle points
- `MonorepoReleasePluginLike.monorepoResourceHooks` lets custom plugins use one shared resource

Legacy raw-process step-list editing was removed. Use hooks and policies instead.

## Hook-based customization

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

releaseIOMonorepoEnablePush := false
releaseIOMonorepoEnablePublish := false
releaseIOMonorepoBeforeTagHooks += markerHook("before-tag")
releaseIOMonorepoAfterTagHooks += markerHook("after-tag")
```

Hook semantics:

- `beforeX` / `afterX` hooks run only when phase `X` is present in the compiled lifecycle
- disabled phases do not run their hooks
- global lifecycle points use `MonorepoGlobalHookIO`
- per-project lifecycle points use `MonorepoProjectHookIO`
- `releaseIOMonorepo check` validates the same lifecycle shape that `releaseIOMonorepo` executes

The tagging lifecycle phase is still named `tag-releases`, but the supported built-in step
symbol is `MonorepoReleaseSteps.tagReleasesPerProject`.

## Migration guide

| Old intent | New hook/policy surface |
| ---------- | ----------------------- |
| Remove `push-changes` | `releaseIOMonorepoEnablePush := false` |
| Remove `publish-artifacts` | `releaseIOMonorepoEnablePublish := false` |
| Remove `run-clean` | `releaseIOMonorepoEnableRunClean := false` |
| Remove `run-tests` | `releaseIOMonorepoEnableRunTests := false` |
| Insert logic before or after project selection | `releaseIOMonorepoBeforeSelectionHooks` / `releaseIOMonorepoAfterSelectionHooks` |
| Insert logic around version resolution | `releaseIOMonorepoBeforeVersionResolutionHooks` / `releaseIOMonorepoAfterVersionResolutionHooks` |
| Insert logic around tagging | `releaseIOMonorepoBeforeTagHooks` / `releaseIOMonorepoAfterTagHooks` |
| Insert logic around publish | `releaseIOMonorepoBeforePublishHooks` / `releaseIOMonorepoAfterPublishHooks` |

### Copy/paste replacements

Disable push and publish:

```scala
releaseIOMonorepoEnablePush := false
releaseIOMonorepoEnablePublish := false
```

Replace "insert a step after project selection" with a global hook:

```scala
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO

releaseIOMonorepoAfterSelectionHooks +=
  MonorepoGlobalHookIO.action("print-selected-projects")(ctx =>
    IO.println(s"[monorepo] selected: ${ctx.currentProjects.map(_.name).mkString(", ")}")
  )
```

Add a per-project notification after tagging:

```scala
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

releaseIOMonorepoAfterTagHooks +=
  MonorepoProjectHookIO.action("notify-tagged") { (_, project) =>
    val version = project.versions.map(_._1).getOrElse("unknown")
    IO.println(s"[monorepo] tagged ${project.name} $version")
  }
```

## Custom plugins with shared resources

Use `MonorepoReleasePluginLike[T]` when release behavior needs one shared resource, such
as an HTTP client. The supported extension point is `monorepoResourceHooks`.

```scala
// project/MyMonorepoRelease.scala
import sbt.*
import _root_.cats.effect.{IO, Resource}
import _root_.io.release.monorepo.*

object MyMonorepoRelease extends MonorepoReleasePluginLike[HttpClient] {
  override def trigger = noTrigger
  override protected def commandName = "releaseMonorepoCustom"

  override def resource: Resource[IO, HttpClient] =
    Resource.make(IO.blocking(new HttpClient()))(client => IO.blocking(client.close()))

  private val validateProjects =
    MonorepoGlobalResourceHookIO.io[HttpClient]("validate-projects")(client => ctx =>
      IO.blocking(client.allowedProjects()).flatMap { allowed =>
        val invalid = ctx.currentProjects.map(_.name).filterNot(allowed.contains)
        if (invalid.nonEmpty)
          IO.raiseError(new RuntimeException(s"Projects not allowed: ${invalid.mkString(", ")}"))
        else IO.pure(ctx)
      }
    )

  private val notifySlack =
    MonorepoGlobalResourceHookIO.action[HttpClient]("notify-slack")(client => ctx =>
      IO.blocking(client.notifyTagged(ctx.currentProjects.map(_.name)))
    )

  override protected def monorepoResourceHooks(
      state: State
  ): MonorepoResourceHooks[HttpClient] =
    MonorepoResourceHooks(
      afterSelectionHooks = Seq(validateProjects),
      afterTagHooks = Seq.empty,
      afterReleaseCommitHooks = Seq(notifySlack)
    )
}
```

Notes:

- `check` validates resource-aware hooks without acquiring the resource
- `run` acquires the resource once, executes compiled hooks, then releases it
- custom monorepo plugins should not define `object autoImport`; reuse the keys from
  `MonorepoReleasePlugin`

## Older API renames

When updating older builds or plugins:

- rename `check` to `validate`
- rename `action` to `execute`
- replace older step convenience factories with the current hook/resource-hook builders
  and the `MonorepoStepIO` APIs where needed internally

## Lower-level step types

`MonorepoStepIO` still exists as the lower-level step type used by built-ins, tests, and
internal composition. It is no longer the supported build-facing customization path for
changing the monorepo pipeline. Prefer hooks and policies in `build.sbt`.
