# Configuration (core)

Use this page for starter `build.sbt` patterns and common configuration recipes. For the
exhaustive settings and CLI catalog, see [Settings reference](reference.md). For a worked
tutorial that sets up policies and hooks and rehearses the result, see
[Customization walkthrough](customization-walkthrough.md).

Use the grouped `releaseIOBehavior*`, `releaseIODefaults*`, `releaseIOPolicy*`,
`releaseIOHooks*`, `releaseIOVersioning*`, `releaseIOVcs*`, and `releaseIOPublish*` names in
`build.sbt`. `inspect` shows the same grouped key labels.

## Starter configuration

In `build.sbt`:

```scala
import _root_.cats.effect.IO
import _root_.io.release.ReleaseHookIO

// Disable push and publish phases
releaseIOPolicyEnablePush    := false
releaseIOPolicyEnablePublish := false

// Add a lifecycle hook before tagging
releaseIOHooksBeforeTag += ReleaseHookIO.sideEffect("before-tag-audit")(ctx =>
  IO.blocking {
    val version = ctx.releaseVersion.getOrElse("unknown")
    ctx.state.log.info(s"[release-io] Auditing tag inputs for $version")
  }
)

// Enable cross-building by default
releaseIOBehaviorCrossBuild := true

// Runtime flag: keep the publish step in the lifecycle, but skip its body and its
// before/after publish hooks at execution time
releaseIOBehaviorSkipPublish := true

// Interactive prompts are disabled by default for CI safety. Enable for guided
// version confirmation and push decisions.
releaseIOBehaviorInteractive := true

// Fail the remote reachability check if it hangs for too long
releaseIOVcsRemoteCheckTimeout := scala.concurrent.duration.DurationInt(30).seconds
```

For custom version-file formats (non-sbt projects, polyglot monorepos), see
[Custom version formats](#custom-version-formats) below.

### Disabling publish: policy vs behavior

Two settings disable publish, and they behave differently:

- `releaseIOPolicyEnablePublish := false` — removes publish from the compiled lifecycle
  entirely, including `beforePublish` / `afterPublish` hooks.
- `releaseIOBehaviorSkipPublish := true` — keeps the publish step in the compiled lifecycle
  but skips its body at execution time. **`releaseIOHooksBeforePublish` and
  `releaseIOHooksAfterPublish` are also gated off** (the gate is decided at validate time
  and stays frozen).

Rehearsal logic that should run in skip-publish mode must live in a non-publish phase —
`releaseIOHooksAfterTag` is the usual fit.

## Example: Persistent decision defaults

Use these shared settings when you want `build.sbt` to pre-answer the built-in
confirmation and tag-conflict decisions during release runs.

```scala
releaseIODefaultsTagExistsAnswer := Some("a")
releaseIODefaultsSnapshotDependenciesAnswer := Some(false)
releaseIODefaultsRemoteCheckFailureAnswer := Some(false)
releaseIODefaultsUpstreamBehindAnswer := Some(false)
releaseIODefaultsPushAnswer := Some(true)
```

## Custom version formats

The default reader and writer assume a `version.sbt` file containing `version := "x.y.z"`
(with or without a `ThisBuild /` prefix). To use a different version file format — for
example, in a non-Scala project or a polyglot monorepo — override these three settings
together. Overriding only the reader without also pointing `releaseIOVersioningFile` at a
compatible file will fail the version bump step.

| Setting | Type | Role |
| ------- | ---- | ---- |
| `releaseIOVersioningFile` | `File` | Path to the version file |
| `releaseIOVersioningReadVersion` | `File => IO[String]` | Extract the current version string from the file |
| `releaseIOVersioningFileContents` | `(File, String) => IO[String]` | Read the existing file and return the new full contents to write back, replacing only the version |

### Example: Java `.properties` file

Given a `version.properties` file:

```
app.name=my-app
app.version=0.1.0-SNAPSHOT
```

Override the settings in `build.sbt`:

```scala
import _root_.cats.effect.IO

releaseIOVersioningFile := baseDirectory.value / "version.properties"

// Parse app.version=x.y.z from the properties file
releaseIOVersioningReadVersion := { (file: File) =>
  IO.blocking(sbt.IO.read(file)).flatMap { contents =>
    val pattern = """app\.version=(.+)""".r
    pattern.findFirstMatchIn(contents) match {
      case Some(m) => IO.pure(m.group(1).trim)
      case None    => IO.raiseError(
        new RuntimeException(s"Could not parse version from ${file.getName}")
      )
    }
  }
}

// Replace only the app.version line, preserve everything else
releaseIOVersioningFileContents := { (file: File, ver: String) =>
  IO.blocking(sbt.IO.read(file)).map { contents =>
    contents.linesIterator
      .map {
        case line if line.startsWith("app.version=") => s"app.version=$ver"
        case line                                    => line
      }
      .mkString("\n") + "\n"
  }
}
```

The same pattern works for any text-based format:

- **Plain text** — a file containing only the version string; the reader returns `IO.blocking(sbt.IO.read(file).trim)` and the writer returns `IO.pure(s"$ver\n")`
- **JSON** — parse `{"version": "x.y.z"}` with a JSON library and produce updated JSON
- **YAML** — match `version: x.y.z` with a regex or YAML parser
