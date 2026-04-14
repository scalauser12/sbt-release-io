# Selective release walkthrough (monorepo)

This walkthrough shows a typical monorepo flow for local rehearsals. Monorepo customization
goes through policy keys and lifecycle hooks — the phase ordering is fixed by the engine,
policies toggle phases on or off, and hooks attach behavior to phases. On top of that, the
monorepo engine adds project selection and change detection. This page covers:

- customizing the flow with hook/policy settings
- letting change detection pick the changed projects
- including downstream dependents automatically
- using explicit selectors only when you want to narrow a rehearsal on purpose

The example uses three projects:

- `core`
- `api` depends on `core`
- `web` depends on `api`

## 1. Add the plugin

`project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.11.0")
```

`project/build.properties`:

```properties
sbt.version=1.12.3
```

Pinning `sbt.version` up front avoids sbt auto-creating `project/build.properties` on first
launch, which would otherwise appear as an untracked file and fail `check-clean-working-dir`.

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
      MonorepoGlobalHookIO(
        name = "print-selected-projects",
        execute = ctx => IO.pure(ctx),
        validate = ctx =>
          IO.println(s"[monorepo] selected: ${ctx.currentProjects.map(_.name).mkString(", ")}")
      )
  )
```

What this config does:

- `push-changes` is disabled
- `publish-artifacts` is disabled
- `run-clean` is disabled to keep the rehearsal faster
- downstream dependents are included when change detection picks an upstream project
- an `afterSelection` hook prints the effective project set during validation, so it appears in
  `check` output

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

Make a committed change to `core`, for example:

```bash
mkdir -p core/src/main/scala
echo "// change" > core/src/main/scala/Core.scala
git add core/src/main/scala/Core.scala
git commit -m "Touch core"
```

The change must be committed: `check-clean-working-dir` runs in validation phase and the
default `releaseIOVcsIgnoreUntrackedFiles := false` rejects both unstaged modifications and
untracked files. Change detection then compares each project against its last release tag, so
the committed file in `core/` is what selects the project.

Now run a no-side-effect rehearsal:

```bash
sbt "releaseIOMonorepo check with-defaults"
```

With `releaseIOMonorepoDetectionIncludeDownstream := true`, the changed `core` project pulls in its
downstream dependents:

- `core` is selected because its files changed since `core/v0.1.0`
- `api` is selected because it depends on `core`
- `web` is selected because it depends on `api`

The `afterSelection` hook prints the effective set during validation, for example:

```text
[monorepo] selected: core, api, web
```

This is the routine path: let change detection decide the base set and use policy keys to make
the rehearsal safe.

## 6. Rehearse an explicit project selection

Sometimes you want to narrow the run intentionally instead of following change detection. Use
explicit selectors and per-project version overrides:

```bash
sbt "releaseIOMonorepo check api with-defaults release-version api=0.2.0 next-version api=0.3.0-SNAPSHOT"
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
- For the full policy and hook surface, see [Settings reference](reference.md) and
  [Customization](customization.md).
- For a simpler first-release setup from scratch, see [First release walkthrough](walkthrough.md).
