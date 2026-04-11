# Customization (monorepo)

## Supported customization model

Monorepo customization has one surface, made of four knobs:

- `releaseIOMonorepoPolicy*` turns built-in phases on or off
- `releaseIOMonorepoHooks*` adds behavior around supported lifecycle points
- `MonorepoReleasePluginLike.monorepoResourceHooks` lets custom plugins use one shared resource
- protected behavior hooks on `MonorepoReleasePluginLike` let custom plugins override
  runtime defaults for cross-build, skip-tests, skip-publish, and interactive mode

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

The tagging lifecycle phase is still named `tag-releases`. Customize around it with
`releaseIOMonorepoHooksBeforeTag` and `releaseIOMonorepoHooksAfterTag`.

## Hook and policy recipes

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
  MonorepoGlobalHookIO.action("announce-clean")(_ =>
    IO.println("[monorepo] clean working tree confirmed")
  )
```

Run a global hook after project selection:

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

For new `.scala` sources, prefer the plugin auto-import explicitly:

```scala
import io.release.monorepo.MonorepoReleasePlugin.autoImport.*
```

When grouped keys are needed in custom Scala build code under `project/`, import them from
`MonorepoReleasePlugin.autoImport.*` or qualify them through `MonorepoReleasePlugin.autoImport`.

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
    MonorepoGlobalResourceHookIO.io[HttpClient]("validate-projects")(client => ctx =>
      IO.blocking(client.allowedProjects()).flatMap { allowed =>
        val invalid = ctx.currentProjects.map(_.name).filterNot(allowed.contains)
        if (invalid.nonEmpty)
          IO.raiseError(new RuntimeException(s"Projects not allowed: ${invalid.mkString(", ")}"))
        else IO.pure(ctx)
      }
    )

  private val notifyTaggedProject =
    MonorepoProjectResourceHookIO.action[HttpClient]("notify-tagged-project")(
      client => (_, project) => {
        val tagName = project.tagName.getOrElse("unknown-tag")
        IO.blocking(client.notifyTagged(project.name, tagName))
      }
    )

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

- `check` validates resource-aware hooks without acquiring the resource
- `run` acquires the resource once, executes compiled hooks, then releases it
- protected behavior hooks default to the corresponding `releaseIOMonorepoBehavior*`
  settings and are intended for custom plugin authors, not ordinary `build.sbt`
  customization
- `MonorepoReleasePluginLike` declares `autoImport` as `final`, so a custom plugin
  inherits the same grouped keys and cannot override them. In `.sbt` files, the keys are
  imported automatically when the plugin is enabled. In `.scala` sources under `project/`,
  import from `MonorepoReleasePlugin.autoImport.*` (or `MyMonorepoRelease.autoImport.*`)
  to bring them into scope.
