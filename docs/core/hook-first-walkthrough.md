# Hook-first walkthrough (core)

This walkthrough shows the current recommended way to customize the core plugin:

- keep the built-in release process intact
- disable mutating remote phases with policy keys
- add small lifecycle hooks around the built-in phases
- use `releaseIO check` for local rehearsal

## 1. Add the plugin

`project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.7.1")
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
    releaseIOEnablePush := false,
    releaseIOEnablePublish := false,
    releaseIOEnableRunClean := false,
    releaseIOAfterCleanCheckHooks += ReleaseHookIO.action("validate-main-branch") { ctx =>
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
    releaseIOBeforeTagHooks += ReleaseHookIO.action("print-tag-banner") { ctx =>
      IO.blocking {
        val version = ctx.releaseVersion.getOrElse("unknown")
        ctx.state.log.info(s"[release-io] Preparing tag for $version")
      }
    }
  )
```

This keeps the default release order and only changes the behavior semantically:

- `push-changes` is disabled
- `publish-artifacts` is disabled
- `run-clean` is disabled for a faster rehearsal
- one hook validates the release branch after the clean-working-dir check
- one hook logs just before tagging

## 4. Inspect the command help

```bash
sbt "releaseIO help"
```

## 5. Rehearse the default plan

```bash
sbt "releaseIO check with-defaults"
```

This validates the same compiled hook/policy shape that the real release would run, but with no
release side effects: no version-file writes, commits, tags, publish, or push.

## 6. Rehearse explicit versions

```bash
sbt "releaseIO check with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

This is useful when you want to confirm tag names, commit messages, and hook execution against a
specific version pair before a real release.

## 7. Run the local-only release

```bash
sbt "releaseIO with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

With push and publish disabled, this creates only local version-file changes, commits, and tags.

## 8. Where to go next

- For the full setting surface, see [Configuration](configuration.md) and [Settings reference](reference.md).
- For hook and resource-hook customization, see [Customization](customization.md).
- For more local rehearsal and CI-oriented patterns, see [Recipes](recipes.md).
