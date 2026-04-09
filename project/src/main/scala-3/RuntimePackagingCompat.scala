import sbt.*
import sbt.Keys.*
import xsbti.HashedVirtualFileRef

object RuntimePackagingCompat {

  def classMappings(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(HashedVirtualFileRef, String)]]] =
    Def.task {
      val converter = fileConverter.value

      (project / Compile / products).value.flatMap { product =>
        if (product.isDirectory)
          Path.allSubpaths(product).toSeq.collect { case (file, path) if file.isFile =>
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
        Path.relativeTo(sourceDirs)(file).orElse(Path.relativeTo(baseDir)(file)).orElse(Path.flat(file))

      (project / Compile / sources).value.flatMap {
        case file if !excluded(file) =>
          relative(file).map(path => (converter.toVirtualFile(file.toPath): HashedVirtualFileRef) -> path)
        case _                       => None
      }
    }
}
