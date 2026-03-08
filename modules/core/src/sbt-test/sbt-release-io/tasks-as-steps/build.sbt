name         := "foo"
organization := "com.example"
scalaVersion := "2.12.18"

lazy val myTask           = taskKey[Unit]("My task")
lazy val myAggregatedTask = taskKey[Unit]("My aggregated task")
lazy val myInputTask      = inputKey[Unit]("My input task")

def writeMarker(baseDir: File, name: String): Unit = {
  val markerDir = baseDir / "marker"
  val marker    = markerDir / name
  IO.createDirectory(markerDir)
  IO.write(marker, "ran")
}

lazy val root = (project in file("."))
  .aggregate(sub)
  .settings(
    name := "foo-root",
    myAggregatedTaskSetting
  )
lazy val sub  = (project in file("sub"))
  .settings(
    name         := "foo-sub",
    scalaVersion := "2.12.18",
    myAggregatedTaskSetting
  )

def myAggregatedTaskSetting = myAggregatedTask := {
  writeMarker(baseDirectory.value, "myaggregatedtask")
}

myTask      := {
  writeMarker(baseDirectory.value, "mytask")
}
myInputTask := {
  val marker = Def.spaceDelimited().parsed.headOption.getOrElse("myinputtask")
  writeMarker(baseDirectory.value, marker)
}

lazy val myCommand       = Command.command("mycommand") { state =>
  writeMarker(Project.extract(state).get(baseDirectory), "mycommand")
  state
}
lazy val myInputCommand  = Command.make("myinputcommand") { state =>
  Def.spaceDelimited().map { args => () =>
    val marker = args.headOption.getOrElse("myinputcommand")
    writeMarker(Project.extract(state).get(baseDirectory), marker)
    state
  }
}
lazy val myCommand2      = Command.command("mycommand2") { state =>
  writeMarker(Project.extract(state).get(baseDirectory), "mycommand2")
  state
}
lazy val myInputCommand2 = Command.make("myinputcommand2") { state =>
  Def.spaceDelimited().map { args => () =>
    val marker = args.headOption.getOrElse("myinputcommand2")
    writeMarker(Project.extract(state).get(baseDirectory), marker)
    state
  }
}

commands ++= Seq(myCommand, myInputCommand, myCommand2, myInputCommand2)

releaseIOProcess := Seq(
  stepTask(myTask),
  stepTaskAggregated(myAggregatedTask),
  stepInputTask(myInputTask),
  stepInputTask(myInputTask, " custominputtask"),
  stepCommand("mycommand"),
  stepCommand("myinputcommand"),
  stepCommand("myinputcommand custominputcommand"),
  stepCommand("mycommand2"),
  stepCommand("myinputcommand2"),
  stepCommand("myinputcommand2 custominputcommand2")
)
