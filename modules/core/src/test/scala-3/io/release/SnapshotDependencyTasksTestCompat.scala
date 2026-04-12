package io.release

import sbt.*
import xsbti.HashedVirtualFileRef

import java.io.File

private[release] object SnapshotDependencyTasksTestCompat:

  def managedClasspathSetting(marker: File, dependencies: Seq[ModuleID]): Setting[?] =
    Test / Keys.managedClasspath := {
      Def.uncached {
        val converter = fileConverter.value
        sbt.IO.write(marker, "ran")
        dependencies.map { dependency =>
          Attributed
            .blank(
              converter.toVirtualFile(
                new File(marker.getParentFile, s"${dependency.name}.jar").toPath
              ): HashedVirtualFileRef
            )
            .put(Keys.moduleIDStr, sbt.Classpaths.moduleIdJsonKeyFormat.write(dependency))
        }
      }
    }
