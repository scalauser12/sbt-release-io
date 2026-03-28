package io.release.internal

/** Shared help-document links used by CLI help output across both plugins.
  *
  * This is the single source of truth for canonical GitHub help URLs. If the project moves,
  * update the base URL here rather than duplicating link changes across help renderers.
  */
private[release] object HelpDocsLinks {

  private val DocsBaseUrl =
    "https://github.com/scalauser12/sbt-release-io/blob/main/docs"

  val CoreReadme: String = s"$DocsBaseUrl/core/README.md"

  val MonorepoReadme: String = s"$DocsBaseUrl/monorepo/README.md"

  val MonorepoUsage: String = s"$DocsBaseUrl/monorepo/usage.md"
}
