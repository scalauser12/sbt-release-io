# Using Typelevel libraries in release steps (core)

Since release steps run in `IO`, you can use any library from the Typelevel / FP ecosystem in your custom steps. This is useful when your release process needs to do more than run sbt tasks and git commands — for example, uploading archives to a file repository, calling REST APIs, or streaming data.

**Constraint:** sbt 1 plugins run on Scala 2.12 and sbt 2 plugins run on Scala 3, so you must use library versions published for the Scala version that matches your sbt version.

Some libraries that work well in release steps:

| Library               | Use case                                          | sbt 1 (Scala 2.12)        | sbt 2 (Scala 3) |
| --------------------- | ------------------------------------------------- | ------------------------- | --------------- |
| `http4s-ember-client` | HTTP requests (upload artifacts, notify services) | 0.23.x (1.x dropped 2.12) | 0.23.x or 1.x   |
| `fs2-io`              | Streaming file I/O, process execution             | 3.x                       | 3.x             |
| `circe`               | JSON encoding/decoding for API calls              | 0.14.x                    | 0.14.x          |
| `doobie`              | JDBC database access (record release metadata)    | 1.x                       | 1.x             |
| `sttp`                | Lightweight HTTP client with cats-effect backend  | 3.x                       | 3.x or 4.x      |

Add the dependency in `project/plugins.sbt` alongside the plugin:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.5.3")
libraryDependencies += "org.http4s" %% "http4s-ember-client" % "0.23.30"
```

Example: compressing an already-built release archive and uploading it to an internal artifact
service after `publishArtifacts`:

```scala
import cats.effect.IO
import fs2.compression.Compression
import fs2.io.file.{Files, Path}
import io.release.ReleaseStepIO
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.{Method, Request, Uri}

val uploadArchive: ReleaseStepIO = ReleaseStepIO.step("upload-archive")
  .execute(ctx =>
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
        ).as(ctx)

      case None =>
        IO.raiseError(new RuntimeException("releaseVersion is not set"))
    }
  )
```

Place this step after `publishArtifacts` so it runs only after the standard repository publish succeeds:

```scala
import io.release.steps.ReleaseSteps

releaseIOProcess := ReleaseSteps.defaults.flatMap {
  case step if step.name == "publish-artifacts" => Seq(step, uploadArchive)
  case step                                     => Seq(step)
}
```
