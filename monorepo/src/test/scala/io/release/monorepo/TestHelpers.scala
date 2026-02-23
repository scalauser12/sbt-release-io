package io.release.monorepo

import java.io.File

private[monorepo] object TestHelpers {

  def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      val children = file.listFiles()
      if (children != null) children.foreach(deleteRecursively)
    }
    file.delete()
    ()
  }
}
