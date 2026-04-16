import sbt.*
import sbt.Keys.*
import xsbti.HashedVirtualFileRef

object RuntimePackagingCompat:

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

  def classMappings(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(HashedVirtualFileRef, String)]]] =
    Def.task {
      val converter = fileConverter.value

      (project / Compile / products).value.flatMap { product =>
        if product.isDirectory then
          Path.allSubpaths(product).toSeq.collect {
            case (file, path) if file.isFile =>
              (converter.toVirtualFile(file.toPath): HashedVirtualFileRef) -> path
          }
        else Nil
      }
    }

  def sourceMappings(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(HashedVirtualFileRef, String)]]] =
    Def.task {
      val converter  = fileConverter.value
      val sourceDirs = (project / Compile / sourceDirectories).value
      val baseDir    = (project / baseDirectory).value
      val excluded   = sourceDirs.toSet + baseDir
      val relative   = (file: File) =>
        Path
          .relativeTo(sourceDirs)(file)
          .orElse(Path.relativeTo(baseDir)(file))
          .orElse(Path.flat(file))

      (project / Compile / sources).value.flatMap {
        case file if !excluded(file) =>
          relative(file).map(path =>
            (converter.toVirtualFile(file.toPath): HashedVirtualFileRef) -> path
          )
        case _                       => None
      }
    }

  def docMappings(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(HashedVirtualFileRef, String)]]] =
    Def.task {
      val converter     = fileConverter.value
      val currentDocDir = (Compile / doc).value
      val projectDocDir = (project / Compile / doc).value
      val existingPaths = currentPaths(currentDocDir)

      Path.allSubpaths(projectDocDir).toSeq.collect {
        case (file, path) if file.isFile && shouldIncludeDoc(path, existingPaths) =>
          (converter.toVirtualFile(file.toPath): HashedVirtualFileRef) -> path
      }
    }
