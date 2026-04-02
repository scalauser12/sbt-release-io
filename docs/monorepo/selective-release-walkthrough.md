# Selective release walkthrough (monorepo)

This walkthrough shows the current recommended monorepo flow for local rehearsals:

- keep the built-in process intact
- customize it with hook/policy settings
- let change detection pick the changed projects
- include downstream dependents automatically
- use explicit selectors only when you want to narrow a rehearsal on purpose

The example uses three projects:

- `core`
- `api` depends on `core`
- `web` depends on `api`

## 1. Add the plugin

`project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.8.0")
```

## 2. Configure the build

`build.sbt`:

```scala
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO

lazy val core = (project in file("core"))
  .settings(name := "core", scalaVersion := "2.12.21")

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(name := "api", scalaVersion := "2.12.21")

lazy val web = (project in file("web"))
  .dependsOn(api)
  .settings(name := "web", scalaVersion := "2.12.21")

lazy val root = (project in file("."))
  .aggregate(core, api, web)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    releaseIOMonorepoPolicyEnablePush := false,
    releaseIOMonorepoPolicyEnablePublish := false,
    releaseIOMonorepoPolicyEnableRunClean := false,
    releaseIOMonorepoDetectionIncludeDownstream := true,
    releaseIOMonorepoHooksAfterSelection +=
      MonorepoGlobalHookIO.action("print-selected-projects")(ctx =>
        IO.println(s"[monorepo] selected: ${ctx.currentProjects.map(_.name).mkString(", ")}")
      )
  )
```

This keeps the built-in release flow, but makes it local-safe:

- `push-changes` is disabled
- `publish-artifacts` is disabled
- `run-clean` is disabled to keep the rehearsal faster
- downstream dependents are included when change detection picks an upstream project
- an `afterSelection` hook prints the effective project set

## 3. Create version files

Create one `version.sbt` per subproject:

```scala
// core/version.sbt
version := "0.2.0-SNAPSHOT"

// api/version.sbt
version := "0.2.0-SNAPSHOT"

// web/version.sbt
version := "0.2.0-SNAPSHOT"
```

## 4. Seed a baseline for change detection

Change detection compares each project to its last release tag, so give the repo a previous
release baseline:

```bash
git init
git add .
git commit -m "Baseline after 0.1.0 release"
git tag core/v0.1.0
git tag api/v0.1.0
git tag web/v0.1.0
```

At this point, the repo represents a monorepo that already released `0.1.0` for each project and
is now on `0.2.0-SNAPSHOT`.

## 5. Rehearse automatic change detection

Modify only `core`, for example:

```bash
echo "// change" >> core/src/main/scala/Core.scala
```

Now run a no-side-effect rehearsal:

```bash
sbt "releaseIOMonorepo check with-defaults"
```

With `releaseIOMonorepoDetectionIncludeDownstream := true`, the changed `core` project pulls in its
downstream dependents:

- `core` is selected because its files changed since `core/v0.1.0`
- `api` is selected because it depends on `core`
- `web` is selected because it depends on `api`

The `afterSelection` hook prints the effective set, for example:

```text
[monorepo] selected: core, api, web
```

This is the recommended default flow for routine releases: let change detection decide the base set
and use policy keys to make the rehearsal safe.

## 6. Rehearse an explicit project selection

Sometimes you want to narrow the run intentionally instead of following change detection. Use
explicit selectors and per-project version overrides:

```bash
sbt "releaseIOMonorepo check api with-defaults release-version api=1.1.0 next-version api=1.2.0-SNAPSHOT"
```

In this mode, `detect-or-select-projects` uses the explicit selector instead of git-based change
detection, so the rehearsal focuses on `api`.

Use this when:

- you want to validate one project's version overrides
- you want to rehearse a targeted release plan before a broader run
- a project name would otherwise collide with CLI keywords and needs `project <id>` syntax

For example, if a project were named `cross`, you would write:

```bash
sbt "releaseIOMonorepo check project cross with-defaults"
```

## 7. Where to go next

- For selector syntax and more CLI examples, see [Usage](usage.md).
- For the underlying git-based selection rules, see [Change detection](change-detection.md).
- For the full policy and hook surface, see [Configuration](configuration.md) and
  [Customization](customization.md).
- For a simpler first-release setup from scratch, see [First release walkthrough](walkthrough.md).
