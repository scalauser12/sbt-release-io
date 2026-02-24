val Scala213 = "2.13.12"
val Scala212 = "2.12.18"

name := "snapshot-deps-cross-test"

scalaVersion := Scala212

crossScalaVersions := Seq(Scala212, Scala213)

// SNAPSHOT dependency only for Scala 2.13 — the default (2.12) is clean.
// Without cross-checked checks, this slips through undetected.
libraryDependencies ++= {
  if (scalaBinaryVersion.value == "2.13")
    Seq("org.example" %% "fake-lib" % "1.0.0-SNAPSHOT")
  else
    Nil
}

releaseIgnoreUntrackedFiles := true

// Skip publish and push steps
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}
