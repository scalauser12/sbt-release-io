package io.release.runtime.preflight

/** Shared rendering helpers for preflight summary output. Pads labels to a per-module width so
  * colon-separated columns align across all rendered lines.
  */
private[release] object PreflightRendering {

  /** Render `"  <label padded to width>: "` — used as the prefix for value-bearing summary lines.
    */
  def pad(label: String, width: Int): String =
    s"  ${label.padTo(width, ' ')}: "

  /** Same as `pad` but without the trailing space — used for label-only headers whose values are
    * appended on subsequent lines (e.g. the monorepo project list header).
    */
  def padLabelOnly(label: String, width: Int): String =
    s"  ${label.padTo(width, ' ')}:"
}
