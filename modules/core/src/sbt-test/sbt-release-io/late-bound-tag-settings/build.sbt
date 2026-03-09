import scala.sys.process.*

name := "late-bound-tag-settings"

scalaVersion := "2.12.18"

releaseIgnoreUntrackedFiles := true

enablePlugins(LateBoundTagPlugin)

releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts" || step.name == "run-tests"
}

val checkLateBoundTag = taskKey[Unit]("Check the late-bound tag")

checkLateBoundTag := {
  val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).toList
  assert(tags == List("late-bound-runtime-tag"), s"Unexpected tags: ${tags.mkString(", ")}")
}
