import scala.sys.process.*

lazy val eligible = (project in file("eligible"))
  .settings(
    name           := "eligible-custom-action",
    scalaVersion   := "2.12.18",
    publish / skip := false,
    publishTo      := Some(Resolver.file("validation-only", target.value / "publish-repo")),
    releaseIOPublishAction :=
      IO.touch(baseDirectory.value / "eligible-published.marker")
  )

lazy val skipped = (project in file("skipped"))
  .settings(
    name                   := "skipped-custom-action",
    scalaVersion           := "2.12.18",
    version                := "9.9.9",
    publish / skip         := true,
    // This custom action deliberately ignores publish / skip. Core must filter its
    // scoped task out before evaluating the selected publish graph.
    releaseIOPublishAction :=
      IO.touch(baseDirectory.value / "skipped-published.marker")
  )

lazy val root = (project in file("."))
  .aggregate(eligible, skipped)
  .settings(
    name           := "publish-skipped-custom-action-filtered",
    scalaVersion   := "2.12.18",
    publish / skip := true,

    releaseIOVcsIgnoreUntrackedFiles := true,
    releaseIOPolicyEnablePush        := false,
    releaseIOPolicyEnableRunClean    := false,
    releaseIOPolicyEnableRunTests    := false
  )
