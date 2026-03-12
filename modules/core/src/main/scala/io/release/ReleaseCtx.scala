package io.release

import _root_.io.release.vcs.Vcs
import sbt.State

/** Common interface for immutable release contexts threaded through steps.
  *
  * Both [[ReleaseContext]] (single-project) and the monorepo `MonorepoContext`
  * implement this trait, enabling shared utilities like [[VcsOps]]
  * to operate polymorphically on either context type.
  *
  * @tparam Self the concrete context type (F-bounded polymorphism)
  */
private[release] trait ReleaseCtx[Self] {
  def state: State
  def vcs: Option[Vcs]
  def failed: Boolean
  def failureCause: Option[Throwable]
  def withState(s: State): Self
  def withVcs(v: Vcs): Self
  def fail: Self
  def failWith(cause: Throwable): Self
}
