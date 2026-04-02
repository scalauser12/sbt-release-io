# Customization (monorepo)

## Supported customization model

Monorepo customization is hook-first:

- `releaseIOMonorepoPolicy*` turns built-in phases on or off
- `releaseIOMonorepoHooks*` adds behavior around supported lifecycle points
- `MonorepoReleasePluginLike.monorepoResourceHooks` lets custom plugins use one shared resource
- protected behavior hooks on `MonorepoReleasePluginLike` let custom plugins override
  runtime defaults for cross-build, skip-tests, skip-publish, and interactive mode

Legacy raw-process step-list editing was removed. Use hooks and policies instead.

## Hook-based customization

```scala
import sbt.*
import sbt.Keys.*
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO
import _root_.io.release.monorepo.MonorepoProjectHookIO

def markerHook(marker: String): MonorepoProjectHookIO =
  MonorepoProjectHookIO.action(marker) { (_, project) =>
    IO.blocking {
      sbt.IO.write(project.baseDir / s"$marker.marker", marker + "\n")
    }
  }

releaseIOMonorepoPolicyEnablePush := false
releaseIOMonorepoPolicyEnablePublish := false
releaseIOMonorepoHooksAfterCleanCheck +=
  MonorepoGlobalHookIO.action("announce-clean")(_ =>
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
- `releaseIOMonorepo check` validates the same lifecycle shape that `releaseIOMonorepo` executes

The tagging lifecycle phase is still named `tag-releases`, but the supported built-in step
symbol is `MonorepoReleaseSteps.tagReleasesPerProject`.

The older flat names remain as deprecated aliases in this release. Prefer the grouped names in
`build.sbt`, even though `inspect` still prints the legacy sbt key labels.

## Key rename guide

| Old name | Preferred grouped name |
| -------- | ---------------------- |
| `releaseIOMonorepoEnablePush` | `releaseIOMonorepoPolicyEnablePush` |
| `releaseIOMonorepoEnablePublish` | `releaseIOMonorepoPolicyEnablePublish` |
| `releaseIOMonorepoEnableRunClean` | `releaseIOMonorepoPolicyEnableRunClean` |
| `releaseIOMonorepoAfterCleanCheckHooks` | `releaseIOMonorepoHooksAfterCleanCheck` |
| `releaseIOMonorepoBeforeSelectionHooks` | `releaseIOMonorepoHooksBeforeSelection` |
| `releaseIOMonorepoAfterSelectionHooks` | `releaseIOMonorepoHooksAfterSelection` |
| `releaseIOMonorepoBeforeTagHooks` | `releaseIOMonorepoHooksBeforeTag` |
| `releaseIOMonorepoAfterTagHooks` | `releaseIOMonorepoHooksAfterTag` |
| `releaseIOMonorepoBeforePublishHooks` | `releaseIOMonorepoHooksBeforePublish` |
| `releaseIOMonorepoAfterPublishHooks` | `releaseIOMonorepoHooksAfterPublish` |

## Migration guide

| Old intent | New hook/policy surface |
| ---------- | ----------------------- |
| Remove `push-changes` | `releaseIOMonorepoPolicyEnablePush := false` |
| Remove `publish-artifacts` | `releaseIOMonorepoPolicyEnablePublish := false` |
| Remove `run-clean` | `releaseIOMonorepoPolicyEnableRunClean := false` |
| Remove `run-tests` | `releaseIOMonorepoPolicyEnableRunTests := false` |
| Insert logic after clean working-dir validation | `releaseIOMonorepoHooksAfterCleanCheck` |
| Insert logic before or after project selection | `releaseIOMonorepoHooksBeforeSelection` / `releaseIOMonorepoHooksAfterSelection` |
| Insert logic around version resolution | `releaseIOMonorepoHooksBeforeVersionResolution` / `releaseIOMonorepoHooksAfterVersionResolution` |
| Insert logic around tagging | `releaseIOMonorepoHooksBeforeTag` / `releaseIOMonorepoHooksAfterTag` |
| Insert logic around publish | `releaseIOMonorepoHooksBeforePublish` / `releaseIOMonorepoHooksAfterPublish` |

### Copy/paste replacements

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
  MonorepoGlobalHookIO.action("announce-clean")(_ =>
    IO.println("[monorepo] clean working tree confirmed")
  )
```

Replace "insert a step after project selection" with a global hook:

```scala
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO

releaseIOMonorepoHooksAfterSelection +=
  MonorepoGlobalHookIO.action("print-selected-projects")(ctx =>
    IO.println(s"[monorepo] selected: ${ctx.currentProjects.map(_.name).mkString(", ")}")
  )
```

Add a per-project notification after tagging:

```scala
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoProjectHookIO

releaseIOMonorepoHooksAfterTag +=
  MonorepoProjectHookIO.action("notify-tagged") { (_, project) =>
    val version = project.versions.map(_._1).getOrElse("unknown")
    IO.println(s"[monorepo] tagged ${project.name} $version")
  }
```

## Custom plugins with shared resources

Use `MonorepoReleasePluginLike[T]` when release behavior needs one shared resource, such
as an HTTP client. The supported extension points are `monorepoResourceHooks` plus the
protected behavior hooks `crossBuildEnabled`, `skipTestsEnabled`, `skipPublishEnabled`,
and `interactiveEnabled`.

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

  override protected def crossBuildEnabled(state: State): Boolean = true

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
- protected behavior hooks default to the corresponding `releaseIOMonorepoBehavior*`
  settings and are intended for custom plugin authors, not ordinary `build.sbt`
  customization
- custom monorepo plugins should not define `object autoImport`; reuse the keys from
  `MonorepoReleasePlugin`

## Older API renames

When updating older builds or plugins:

- rename `check` to `validate`
- rename `action` to `execute`
- replace older step convenience factories with the current hook/resource-hook builders
- keep the deprecated `MonorepoStepIO` APIs only for built-ins, tests, and internal composition

## Lower-level step types

`MonorepoStepIO` is deprecated and retained only as the lower-level step type used by built-ins,
tests, and internal composition. It is no longer the supported build-facing customization path
for changing the monorepo pipeline. Prefer hooks, resource hooks, and grouped hook/policy
settings in `build.sbt`.
