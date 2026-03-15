package io.release.monorepo

final case class MonorepoProjectFailure(
    projectName: String,
    cause: Option[Throwable]
)

final class MonorepoProjectFailures(
    val failures: Seq[MonorepoProjectFailure]
) extends IllegalStateException(
      MonorepoProjectFailures.message(failures),
      failures.flatMap(_.cause).headOption.orNull
    ) {

  failures.flatMap(_.cause).drop(1).foreach(addSuppressed)
}

object MonorepoProjectFailures {

  private def message(failures: Seq[MonorepoProjectFailure]): String = {
    val rendered = failures.map { failure =>
      failure.cause match {
        case Some(err) =>
          s"${failure.projectName}: ${Option(err.getMessage).getOrElse(err.toString)}"
        case None      => s"${failure.projectName}: failed"
      }
    }

    s"Per-project release failures:\n${rendered.map("  " + _).mkString("\n")}"
  }
}
