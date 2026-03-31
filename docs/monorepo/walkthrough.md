# First release walkthrough (monorepo)

This walkthrough sets up a two-project monorepo from scratch and runs the first release. It mirrors the `simple-monorepo` scripted test.

## Directory structure

```
my-monorepo/
├── build.sbt
├── project/
│   └── plugins.sbt
├── core/
│   └── version.sbt
└── api/
    └── version.sbt
```

## 1. Add the plugin

`project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.7.1")
```

## 2. Configure the build

`build.sbt`:

```scala
lazy val core = (project in file("core"))
  .settings(name := "core", scalaVersion := "2.12.21")

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(name := "api", scalaVersion := "2.12.21")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    // Disable push and publish during initial setup — re-enable when ready
    releaseIOMonorepoEnablePush    := false,
    releaseIOMonorepoEnablePublish := false
  )
```

## 3. Create version files

`core/version.sbt` and `api/version.sbt`:

```scala
version := "0.1.0-SNAPSHOT"
```

This walkthrough uses the per-project model, so each subproject gets its own `version.sbt`.

## 4. Initialise git and make the first commit

```bash
git init
git add .
git commit -m "Initial commit"
```

## 5. Inspect the command help

```bash
sbt "releaseIOMonorepo help"
```

## 6. Run a local rehearsal

```bash
sbt "releaseIOMonorepo check with-defaults"
```

The preflight has no release side effects: it resolves the selected projects, versions, and tags, then runs validations without writing version files, creating commits or tags, publishing artifacts, or pushing to a remote. With cross-build validation enabled, sbt may temporarily switch Scala versions during validation and then restore the entry version.

## 7. Run the first release

```bash
sbt "releaseIOMonorepo with-defaults"
```

The plugin runs the [default release steps](concepts.md#default-release-steps) in order — sorting projects by dependency, computing versions, writing version files, committing, and tagging. Push and publish are disabled in this walkthrough; re-enable them once confident.

After the release:

```bash
git log --oneline     # 3 commits: Initial, release versions, next versions
git tag               # core/v0.1.0  api/v0.1.0
cat core/version.sbt  # version := "0.2.0-SNAPSHOT"
```

> **Note:** The first release triggers all projects as changed because change detection looks for a prior release tag and finds none. On subsequent runs, only projects with file changes since their last tag are released. To force all projects regardless, use the `all-changed` flag.

> **Local rehearsal:** The walkthrough above already disables `push-changes` and `publish-artifacts` via hook/policy settings. Use the same pattern together with `releaseIOMonorepo check` to rehearse any release locally before running the real command. To undo a rehearsal run, see [Recovery and Rollback](operations.md#recovery-and-rollback).
