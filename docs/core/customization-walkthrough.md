# Customization walkthrough (core)

This walkthrough shows how to customize the core plugin. Customization is done through policy
keys and lifecycle hooks. Phase ordering is fixed by the engine; policies toggle phases on or
off, hooks attach behavior to phases. This page walks through a typical setup:

- disable mutating remote phases with policy keys
- add small lifecycle hooks around the built-in phases
- use `releaseIO check` for local rehearsal

> **Prerequisites:** an existing git-initialized Scala project with a clean working tree, on
> branch `main` or `master` (the `validate-main-branch` hook below enforces this). If you are
> adding the plugin to an existing project, commit any outstanding changes before running the
> rehearsal.

## 1. Add the plugin

`project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.13.1")
```

## 2. Add a version file

Create `version.sbt`:

```scala
ThisBuild / version := "0.3.0-SNAPSHOT"
```

## 3. Configure a safe rehearsal flow

`build.sbt`:

```scala
import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

lazy val root = (project in file("."))
  .settings(
    releaseIOPolicyEnablePush := false,
    releaseIOPolicyEnablePublish := false,
    releaseIOPolicyEnableRunClean := false,
    releaseIOHooksAfterCleanCheck += ReleaseHookIO.precondition("validate-main-branch") { ctx =>
      ctx.vcs match {
        case Some(vcs) =>
          vcs.currentBranch.flatMap { branch =>
            if (branch == "main" || branch == "master") IO.unit
            else IO.raiseError(new RuntimeException(s"Release from main/master only, not $branch"))
          }
        case None =>
          IO.raiseError(new RuntimeException("VCS not initialized"))
      }
    },
    releaseIOHooksBeforeTag += ReleaseHookIO.sideEffect("print-tag-banner") { ctx =>
      IO.blocking {
        val version = ctx.releaseVersion.getOrElse("unknown")
        ctx.state.log.info(s"[release-io] Preparing tag for $version")
      }
    }
  )
```

What this config does:

- `push-changes` is disabled
- `publish-artifacts` is disabled
- `run-clean` is disabled for a faster rehearsal
- one hook validates the release branch after the clean-working-dir check
- one hook logs just before tagging

If you are starting from a fresh directory, initialize the repo before continuing:

```bash
git init && git add . && git commit -m "Initial commit"
```

## 4. Inspect the command help

```bash
sbt "releaseIO help"
```

## 5. Rehearse the default plan

```bash
sbt "releaseIO check with-defaults"
```

This validates the same hook and policy configuration the real release would run, but with no
release side effects: no version-file writes, commits, tags, publish, or push.

## 6. Rehearse explicit versions

```bash
sbt "releaseIO check with-defaults release-version 0.3.0 next-version 0.4.0-SNAPSHOT"
```

This is useful when you want to confirm commit messages and hook execution against a specific
version pair before a real release. Version and tag summaries remain available as long as no
execute-time hook rewrites them.

## 7. Run the local-only release

```bash
sbt "releaseIO with-defaults release-version 0.3.0 next-version 0.4.0-SNAPSHOT"
```

With push and publish disabled, this creates only local version-file changes, two commits
(`commit-release-version` and `commit-next-version`), and one tag (`v0.3.0`).

Inspect the result:

```bash
git log --oneline -5
git tag
cat version.sbt
```

To clean up after the rehearsal, verify that the last two commits are the release commits,
then delete the tag and roll back. `git reset --hard` discards uncommitted working-tree
changes, so commit or stash anything else first.

```bash
git log -2 --oneline         # should show the two release commits
git tag -d v0.3.0
git reset --hard HEAD~2
```

For rollback in other scenarios (partial release, push already happened), see
[Recovery and rollback](operations.md#recovery-and-rollback).

## 8. Where to go next

- For the full setting surface, see [Configuration](configuration.md) and [Settings reference](reference.md).
- For hook and resource-hook customization, see [Customization](customization.md).
- For more local rehearsal and CI-oriented patterns, see [Recipes](recipes.md).
