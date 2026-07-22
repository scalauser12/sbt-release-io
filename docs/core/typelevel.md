# Using Typelevel libraries in hooks and custom plugins (core)

Since release hooks, resource hooks, and lower-level release internals run in `IO`, you can use
any library from the Typelevel / FP ecosystem in your custom release logic. This is useful when
your release process needs to do more than run sbt tasks and git commands — for example,
uploading archives to a file repository, calling REST APIs, or streaming data.

**Constraint:** sbt 1 plugins run on Scala 2.12; sbt 2 plugins run on Scala 3. Pick a library version published for the Scala version that matches your sbt line.

Some libraries that work well in hook bodies and resource-aware custom plugins:

| Library               | Use case                                          | sbt 1 (Scala 2.12)               | sbt 2 (Scala 3)        |
| --------------------- | ------------------------------------------------- | -------------------------------- | ---------------------- |
| `http4s-ember-client` | HTTP requests (upload artifacts, notify services) | 0.23.x (stable)                   | 0.23.x or 1.0 milestones |
| `fs2-io`              | Streaming file I/O, process execution             | 3.x                              | 3.x                    |
| `circe`               | JSON encoding/decoding for API calls              | 0.14.x                           | 0.14.x                 |
| `doobie`              | JDBC database access (record release metadata)    | 1.0.0-RC releases                | 1.0.0-RC releases      |
| `sttp-client3` / `sttp-client4` | Lightweight HTTP client with cats-effect backend | 3.x (`client3`) or 4.x (`client4`) | 3.x (`client3`) or 4.x (`client4`) |

The [http4s version matrix](https://http4s.org/versions.html) identifies 0.23 as the stable line;
the Scala 2.13/3-only 1.0 line is still published as milestones. The
[doobie documentation](https://typelevel.org/doobie/) currently documents 1.0 release candidates
for Scala 2.12, 2.13, and 3. The
[sttp 4 backend matrix](https://sttp.softwaremill.com/en/stable/backends/summary.html) confirms that
its backends, including cats-effect backends, are available for Scala 2.12 as well as Scala 2.13
and 3. Check those upstream pages for current versions before choosing a dependency.

Add the dependency in `project/plugins.sbt` alongside the plugin. This adds the library to the
*build's* classpath, where hooks run — not to your project's runtime dependencies.

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.13.7")
libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-ember-client" % "0.23.30",
  "co.fs2"     %% "fs2-io"              % "3.11.0"
)
```

Example: upload a single Scala-version-agnostic release archive to an artifact service after
the next-version commit. Because it is a single, version-agnostic upload, attach it to
`releaseIOHooksAfterNextCommit` rather than `afterPublish` (see the cross-build gotcha below):

```scala
import _root_.cats.effect.IO
import fs2.compression.Compression
import fs2.io.file.{Files, Path}
import _root_.io.release.{ReleaseContext, ReleaseHookIO}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.{Method, Request, Uri}

def uploadArchive(ctx: ReleaseContext): IO[Unit] =
  ctx.releaseVersion match {
    case Some(version) =>
      val archivePath = Path(s"target/myproject-$version.tar")
      val uploadUri   =
        Uri.unsafeFromString(
          s"https://artifacts.example.com/releases/myproject-$version.tar.gz"
        )
      val body        = Files[IO].readAll(archivePath).through(Compression[IO].gzip())
      val request     = Request[IO](Method.PUT, uploadUri).withBodyStream(body)

      EmberClientBuilder.default[IO].build.use(client =>
        client.expectOr[Unit](request)(response =>
          response.as[String].map(errorBody =>
            new RuntimeException(s"Artifact upload failed (${response.status}): $errorBody")
          )
        )
      )

    case None =>
      IO.raiseError(new RuntimeException("releaseVersion is not set"))
  }

releaseIOHooksAfterNextCommit += ReleaseHookIO.sideEffect("upload-archive")(uploadArchive)
```

**Cross-build gotcha:** `releaseIOHooksBeforePublish` and `releaseIOHooksAfterPublish` are the
only hook slots that inherit cross-build iteration. With `releaseIOBehaviorCrossBuild := true`,
hooks in these slots run **once per Scala version** in `crossScalaVersions`. Use them when
your side effect is per Scala version (uploading a per-version artifact, notifying per-version
consumers). For side effects that should run exactly once per release (a Scala-version-agnostic
archive like the example above, a single release notification), attach to the unconditional,
non-cross-built `releaseIOHooksAfterNextCommit` slot.

`releaseIOHooksAfterPush` is also non-cross-built, but it runs only when `push-changes` actually
updates the remote. Disabling push, declining it, or leaving it unanswered in non-interactive mode
suppresses that hook; use `releaseIOHooksAfterNextCommit` when the action must run regardless of
the push decision.
