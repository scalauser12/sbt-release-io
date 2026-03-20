# Configuration (core)

## Configuration

In `build.sbt`:

```scala
import cats.effect.IO
import io.release.steps.ReleaseSteps

// Use default release steps (recommended)
releaseIOProcess := ReleaseSteps.defaults

// Or customize the release process (see [Customization](customization.md))
releaseIOProcess := releaseIOProcess.value.filterNot(_.name == "push-changes")

// Enable cross-building by default
releaseIOCrossBuild := true

// Skip publish during release
releaseIOSkipPublish := true

// Enable interactive prompts (disabled by default)
releaseIOInteractive := true

// Custom version file reader (default parses `[ThisBuild /] version := "x.y.z"`)
releaseIOReadVersion := (file =>
  IO.blocking(sbt.IO.read(file).trim)
)

// Custom version file content (default produces `ThisBuild / version := "x.y.z"\n`)
releaseIOVersionFileContents := ((_, version) =>
  IO.pure(s"$version\n")
)
```

## Custom version formats

The default reader and writer assume a `version.sbt` file containing `[ThisBuild /] version := "x.y.z"`. To use a different version file format — for example, in a non-Scala project or a polyglot monorepo — override three settings together:

| Setting                        | Role                                                                               |
| ------------------------------ | ---------------------------------------------------------------------------------- |
| `releaseIOVersionFile`         | Path to the version file                                                           |
| `releaseIOReadVersion`         | `File => IO[String]` — extract the version string from the file                    |
| `releaseIOVersionFileContents` | `(File, String) => IO[String]` — returns the version file content to write to disk |

The function receives the current file as its first argument, so it can read existing content and replace only the version line while preserving other fields.

### Example: Java `.properties` file

Given a `version.properties` file:

```
app.name=my-app
app.version=0.1.0-SNAPSHOT
```

Override the settings in `build.sbt`:

```scala
import cats.effect.IO

releaseIOVersionFile := baseDirectory.value / "version.properties"

// Parse app.version=x.y.z from the properties file
releaseIOReadVersion := { (file: File) =>
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
releaseIOVersionFileContents := { (file: File, ver: String) =>
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
