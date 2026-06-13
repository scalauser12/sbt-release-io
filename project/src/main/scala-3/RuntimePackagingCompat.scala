import sbt.*
import sbt.Keys.*
import xsbti.HashedVirtualFileRef

/** sbt 2 packaging mappings: convert the shared `File`-keyed mappings to `HashedVirtualFileRef`.
  * The conversion re-enters task context with its own `Def.task` because `fileConverter.value`
  * is only available inside a task macro.
  */
object RuntimePackagingCompat:

  private def toVirtual(
      raw: Def.Initialize[Task[Seq[(File, String)]]]
  ): Def.Initialize[Task[Seq[(HashedVirtualFileRef, String)]]] =
    Def.task {
      val converter = fileConverter.value
      raw.value.map { case (file, path) =>
        (converter.toVirtualFile(file.toPath): HashedVirtualFileRef) -> path
      }
    }

  def classMappings(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(HashedVirtualFileRef, String)]]] =
    toVirtual(RuntimePackaging.classMappingsRaw(project))

  def sourceMappings(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(HashedVirtualFileRef, String)]]] =
    toVirtual(RuntimePackaging.sourceMappingsRaw(project))

  def docMappings(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(HashedVirtualFileRef, String)]]] =
    toVirtual(RuntimePackaging.docMappingsRaw(project))
