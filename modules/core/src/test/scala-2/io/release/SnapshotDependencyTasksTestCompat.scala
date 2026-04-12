package io.release

import sbt.*

import java.io.File

private[release] object SnapshotDependencyTasksTestCompat {

  def managedClasspathSetting(marker: File, dependencies: Seq[ModuleID]): Setting[?] =
    Test / Keys.managedClasspath := {
      sbt.IO.write(marker, "ran")
      dependencies.map { dependency =>
        Attributed
          .blank(new File(marker.getParentFile, s"${dependency.name}.jar"))
          .put(Keys.moduleID.key, dependency)
      }
    }
}
