import sbt._
import sbt.Keys._

object RuntimePackagingCompat {

  def classMappings(
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

  def sourceMappings(
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
}
