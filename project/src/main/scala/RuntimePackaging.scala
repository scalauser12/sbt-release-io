import sbt._
import sbt.Keys._

/** Version-neutral packaging-mapping logic shared by the sbt 1 and sbt 2 `RuntimePackagingCompat`
  * shims. Produces `File`-keyed mappings; the sbt 2 shim converts the keys to
  * `HashedVirtualFileRef` (which requires a task-scoped `fileConverter`), the sbt 1 shim uses them
  * as-is.
  */
object RuntimePackaging {

  private def currentPaths(docDir: File): Set[String] =
    Path
      .allSubpaths(docDir)
      .toSeq
      .collect {
        case (file, path) if file.isFile => path
      }
      .toSet

  private def shouldIncludeDoc(path: String, existingPaths: Set[String]): Boolean =
    path.endsWith(".html") &&
      (path.startsWith("io/release/") || path.startsWith("sbt/")) &&
      !existingPaths.contains(path)

  def classMappingsRaw(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(File, String)]]] =
    Def.task {
      (project / Compile / products).value.flatMap { product =>
        if (product.isDirectory)
          Path.allSubpaths(product).toSeq.collect {
            case (file, path) if file.isFile => file -> path
          }
        else Nil
      }
    }

  def sourceMappingsRaw(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(File, String)]]] =
    Def.task {
      val sourceDirs = (project / Compile / sourceDirectories).value
      val baseDir    = (project / baseDirectory).value
      val excluded   = sourceDirs.toSet + baseDir
      val relative   = (file: File) =>
        Path
          .relativeTo(sourceDirs)(file)
          .orElse(Path.relativeTo(baseDir)(file))
          .orElse(Path.flat(file))

      (project / Compile / sources).value.flatMap {
        case file if !excluded(file) => relative(file).map(file -> _)
        case _                       => None
      }
    }

  def docMappingsRaw(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(File, String)]]] =
    Def.task {
      val currentDocDir = (Compile / doc).value
      val projectDocDir = (project / Compile / doc).value
      val existingPaths = currentPaths(currentDocDir)

      Path.allSubpaths(projectDocDir).toSeq.collect {
        case (file, path) if file.isFile && shouldIncludeDoc(path, existingPaths) =>
          file -> path
      }
    }
}
