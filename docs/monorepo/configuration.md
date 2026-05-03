# Configuration (monorepo)

Use this page for starter `build.sbt` patterns and common configuration recipes. For the
exhaustive settings catalog, see [Settings reference](reference.md). For an onboarding
tutorial, start with [Getting started](getting-started.md). For CLI syntax and examples,
see [Usage](usage.md).

Settings prefixed `releaseIOMonorepo` are the monorepo-specific layer. Shared/core settings
prefixed `releaseIO` are owned by the core plugin and are available transitively because
`MonorepoReleasePlugin` requires `ReleasePluginIO` — including `releaseIODefaults*` for
decision defaults and `releaseIOVcsRemoteCheckTimeout` for the pre-push remote check.

## Example: Starter configuration

Monorepo-specific settings:

```scala
releaseIOMonorepoBehaviorSkipTests  := true
releaseIOMonorepoBehaviorCrossBuild := true
releaseIOMonorepoVcsTagName         := { (name, ver) => s"release/$name/$ver" }
```

Shared (inherited from core):

```scala
releaseIOVcsRemoteCheckTimeout := scala.concurrent.duration.DurationInt(30).seconds
```

When you override `releaseIOMonorepoVcsTagName`, change-detection's tag-lookup pattern
follows automatically — see [How change detection works](change-detection.md#how-it-works).

### Disabling publish: policy vs behavior

Two settings disable publish, and they behave differently:

- `releaseIOMonorepoPolicyEnablePublish := false` — removes publish from the compiled
  lifecycle entirely, including `beforePublish` / `afterPublish` hooks.
- `releaseIOMonorepoBehaviorSkipPublish := true` — keeps the publish step in the compiled
  lifecycle but skips its body at execution time. **`releaseIOMonorepoHooksBeforePublish`
  and `releaseIOMonorepoHooksAfterPublish` are also gated off** (the gate is decided at
  validate time and stays frozen).

Rehearsal logic that should run in skip-publish mode must live in a non-publish phase —
`releaseIOMonorepoHooksAfterTag` is the usual fit.

## Example: Persistent decision defaults

Use these shared settings when you want `build.sbt` to pre-answer the built-in monorepo
confirmation and tag-conflict decisions. They do not affect project selection or version
override syntax.

```scala
releaseIODefaultsTagExistsAnswer            := Some("a")
releaseIODefaultsSnapshotDependenciesAnswer := Some(false)
releaseIODefaultsRemoteCheckFailureAnswer   := Some(false)
releaseIODefaultsUpstreamBehindAnswer       := Some(false)
releaseIODefaultsPushAnswer                 := Some(true)
```

## Custom version formats

The default monorepo reader and writer assume each project has its own `version.sbt`
file containing `version := "x.y.z"`. To use a different version-file format — for example,
non-Scala subprojects in a polyglot monorepo — override these three settings together.
Overriding only the reader without also pointing `releaseIOMonorepoVersioningFile` at a
compatible file will fail the version bump step.

| Setting | Type | Role |
| ------- | ---- | ---- |
| `releaseIOMonorepoVersioningFile` | `(ProjectRef, State) => File` | Resolve the version file per project. Use the `ProjectRef` to switch formats per subproject |
| `releaseIOMonorepoVersioningReadVersion` | `File => IO[String]` | Extract the current version string from the file |
| `releaseIOMonorepoVersioningFileContents` | `(File, String) => IO[String]` | Read the existing file and return the new full contents to write back, replacing only the version |

### Example: per-project mixed formats

Use a `version.properties` file for one subproject and `version.sbt` for the rest:

```scala
import _root_.cats.effect.IO

releaseIOMonorepoVersioningFile := { (ref, state) =>
  val base = Project.extract(state).get(ref / baseDirectory)
  ref.project match {
    case "py-service" => base / "version.properties"
    case _            => base / "version.sbt"
  }
}

releaseIOMonorepoVersioningReadVersion := { (file: File) =>
  IO.blocking(sbt.IO.read(file)).flatMap { contents =>
    if (file.getName == "version.properties") {
      val pattern = """app\.version=(.+)""".r
      pattern.findFirstMatchIn(contents) match {
        case Some(m) => IO.pure(m.group(1).trim)
        case None    => IO.raiseError(
          new RuntimeException(s"Could not parse version from ${file.getName}")
        )
      }
    } else {
      val pattern = """version\s*:=\s*"([^"]+)"""".r
      pattern.findFirstMatchIn(contents) match {
        case Some(m) => IO.pure(m.group(1))
        case None    => IO.raiseError(
          new RuntimeException(s"Could not parse version from ${file.getName}")
        )
      }
    }
  }
}

releaseIOMonorepoVersioningFileContents := { (file: File, ver: String) =>
  IO.blocking(sbt.IO.read(file)).map { contents =>
    if (file.getName == "version.properties") {
      contents.linesIterator
        .map {
          case line if line.startsWith("app.version=") => s"app.version=$ver"
          case line                                    => line
        }
        .mkString("\n") + "\n"
    } else {
      s"""version := "$ver"\n"""
    }
  }
}
```

Same pattern as the [core custom version formats recipe](../core/configuration.md#custom-version-formats),
adapted to the per-project signature: the file resolver takes `(ProjectRef, State)` so each
subproject can pick its own format.
