package sbt

import sbt.internal.*
import sbt.nio.{Settings as NioSettings}

/** Test-only adapter over sbt loader internals. Expect to update it when bumping sbt. */
object TestBuildState {

  def apply(
      baseState: State,
      baseDir: File,
      projects: Seq[Project],
      buildSettings: Seq[Setting[_]] = Nil,
      currentProjectId: Option[String] = None
  ): State = {
    require(projects.nonEmpty, "Synthetic test states require at least one project.")

    val canonicalBase = baseDir.getCanonicalFile
    val uri           = canonicalBase.toURI
    val rootProjectIds = {
      val atBase = projects.filter(_.base.getCanonicalFile == canonicalBase).map(_.id)
      if (atBase.nonEmpty) atBase else Seq(projects.head.id)
    }
    val rootProjectId = rootProjectIds.head

    def resolveRef(ref: ProjectReference): ProjectRef =
      ref match {
        case pr: ProjectRef   => pr
        case LocalProject(id) => ProjectRef(uri, id)
        case LocalRootProject => ProjectRef(uri, rootProjectId)
        case RootProject(`uri`) =>
          ProjectRef(uri, rootProjectId)
        case RootProject(other) =>
          ProjectRef(other, rootProjectId)
        case other              =>
          throw new IllegalArgumentException(
            s"Unsupported project reference in synthetic test state: $other"
          )
      }

    val resolvedProjects = projects.iterator.map(p => p.id -> p.resolve(resolveRef)).toMap
    val buildDefBase     = new File(canonicalBase, "project")
    val definitions      = new LoadedDefinitions(
      base = buildDefBase,
      target = Nil,
      loader = getClass.getClassLoader,
      builds = Seq(BuildDef.defaultEmpty),
      projects = projects,
      buildNames = Nil,
      dslDefinitions = DefinedSbtValues.empty
    )
    val plugins          = new LoadedPlugins(
      base = buildDefBase,
      pluginData = PluginData(Seq.empty[sbt.internal.util.Attributed[java.io.File]]),
      loader = getClass.getClassLoader,
      detected = new DetectedPlugins(Nil, new DetectedModules[BuildDef](Nil))
    )
    val unit             =
      new BuildUnit(uri, canonicalBase, definitions, plugins)
    val loadedUnit       =
      new LoadedBuildUnit(unit, resolvedProjects, rootProjectIds, buildSettings)
    val units            = Map(uri -> loadedUnit)
    val loaded           = new LoadedBuild(uri, units)
    val delegates        = Load.defaultDelegates(loaded)
    val scopeLocal: Def.ScopeLocal =
      state => EvaluateTask.injectStreams(state) ++ NioSettings.inject(state)
    val inject           = Load.InjectSettings(Load.injectGlobal(baseState), Nil, _ => Nil)
    val settings         =
      Load.finalTransforms(
        Load.buildConfigurations(loaded, Load.getRootProject(units), inject)
      )
    implicit val showKey: sbt.util.Show[Def.ScopedKey[_]] =
      Project.showLoadingKey(loaded)
    val (compiledMap, data) =
      Def.makeWithCompiledMap(settings)(delegates, scopeLocal, showKey)
    val index               =
      Load.structureIndex(data, settings, loaded.extra(data), units)
    val structure           = new BuildStructure(
      units = units,
      root = uri,
      settings = settings,
      data = data,
      index = index,
      streams = BuildStreams.mkStreams(units, uri, data),
      delegates = delegates,
      scopeLocal = scopeLocal,
      compiledMap = compiledMap
    )
    val session             = SessionSettings(
      currentBuild = uri,
      currentProject = Map(uri -> currentProjectId.getOrElse(rootProjectId)),
      original = settings,
      append = Map.empty,
      rawAppend = Nil,
      currentEval = () =>
        throw new IllegalStateException(
          "Synthetic test states do not support compiling session settings from strings."
        )
    )

    Project.setProject(session, structure, baseState)
  }
}
