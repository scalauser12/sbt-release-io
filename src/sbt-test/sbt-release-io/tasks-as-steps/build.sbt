name         := "foo"
organization := "com.example"
version      := "1.2.3"
scalaVersion := "2.12.18"

lazy val myTask           = taskKey[Unit]("My task")
lazy val myAggregatedTask = taskKey[Unit]("My aggregated task")
lazy val myInputTask      = inputKey[Unit]("My input task")

lazy val root = (project in file("."))
  .aggregate(sub)
  .settings(myAggregatedTaskSetting)
lazy val sub  = (project in file("sub"))
  .settings(
    scalaVersion := "2.12.18",
    myAggregatedTaskSetting
  )

def myAggregatedTaskSetting = myAggregatedTask := {
  IO.write(target.value / "myaggregatedtask", "ran")
}

myTask      := {
  IO.write(target.value / "mytask", "ran")
}
myInputTask := {
  val marker = Def.spaceDelimited().parsed.headOption.getOrElse("myinputtask")
  IO.write(target.value / marker, "ran")
}

lazy val myCommand       = Command.command("mycommand") { state =>
  IO.write(Project.extract(state).get(target) / "mycommand", "ran")
  state
}
lazy val myInputCommand  = Command.make("myinputcommand") { state =>
  Def.spaceDelimited().map { args => () =>
    val marker = args.headOption.getOrElse("myinputcommand")
    IO.write(Project.extract(state).get(target) / marker, "ran")
    state
  }
}
lazy val myCommand2      = Command.command("mycommand2") { state =>
  IO.write(Project.extract(state).get(target) / "mycommand2", "ran")
  state
}
lazy val myInputCommand2 = Command.make("myinputcommand2") { state =>
  Def.spaceDelimited().map { args => () =>
    val marker = args.headOption.getOrElse("myinputcommand2")
    IO.write(Project.extract(state).get(target) / marker, "ran")
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
