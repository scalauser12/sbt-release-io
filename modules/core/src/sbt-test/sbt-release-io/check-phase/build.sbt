import sbt.IO
import _root_.io.release.{ReleaseContext, ReleaseStepIO}
import _root_.io.release.steps.ReleaseSteps

name                        := "check-phase-test"
scalaVersion                := "2.12.18"
releaseIgnoreUntrackedFiles := true

// Step whose check always fails; action creates a marker file to prove it ran
val stepWithFailingCheck = ReleaseStepIO(
  name = "step-with-failing-check",
  action = (ctx: ReleaseContext) => _root_.cats.effect.IO { IO.touch(file("action-ran")); ctx },
  check = (_: ReleaseContext) =>
    _root_.cats.effect.IO.raiseError(new RuntimeException("check always fails"))
)

releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  stepWithFailingCheck
)
