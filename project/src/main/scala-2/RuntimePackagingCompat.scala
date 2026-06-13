import sbt._

/** sbt 1 packaging mappings: the shared `File`-keyed mappings are used as-is. */
object RuntimePackagingCompat {

  def classMappings(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(File, String)]]] =
    RuntimePackaging.classMappingsRaw(project)

  def sourceMappings(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(File, String)]]] =
    RuntimePackaging.sourceMappingsRaw(project)

  def docMappings(
      project: ProjectReference
  ): Def.Initialize[Task[Seq[(File, String)]]] =
    RuntimePackaging.docMappingsRaw(project)
}
