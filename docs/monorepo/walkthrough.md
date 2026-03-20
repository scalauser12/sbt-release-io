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
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.5.3")
```

## 2. Configure the build

`build.sbt`:

```scala
lazy val core = (project in file("core"))
  .settings(name := "core", scalaVersion := "2.12.18")

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(name := "api", scalaVersion := "2.12.18")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    // Filter out push and publish during initial setup — re-enable when ready
    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot(step =>
      step.name == "push-changes" || step.name == "publish-artifacts"
    )
  )
```

## 3. Create version files

`core/version.sbt` and `api/version.sbt`:

```scala
version := "0.1.0-SNAPSHOT"
```

## 4. Initialise git and make the first commit

```bash
git init
git add .
git commit -m "Initial commit"
```

## 5. Run the first release

```bash
sbt "releaseIOMonorepo with-defaults"
```

The plugin runs the [default release steps](concepts.md#default-release-steps) in order — sorting projects by dependency, computing versions, writing version files, committing, and tagging. Push is filtered out in this walkthrough; re-enable once confident.

After the release:

```bash
git log --oneline     # 3 commits: Initial, release versions, next versions
git tag               # core/v0.1.0  api/v0.1.0
cat core/version.sbt  # version := "0.2.0-SNAPSHOT"
```

> **Note:** The first release triggers all projects as changed because change detection looks for a prior release tag and finds none. On subsequent runs, only projects with file changes since their last tag are released. To force all projects regardless, use the `all-changed` flag.

> **Dry run:** The walkthrough above already filters out `push-changes` and `publish-artifacts`. Use this same `filterNot` pattern to rehearse any release without side effects. To undo a dry run, see [Recovery and Rollback](operations.md#recovery-and-rollback).
