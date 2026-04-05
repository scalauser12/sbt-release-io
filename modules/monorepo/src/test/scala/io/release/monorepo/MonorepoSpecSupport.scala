package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.TestSupport
import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseDecisionDefaults
import io.release.internal.SbtRuntime
import io.release.vcs.Vcs
import munit.Assertions.fail
import sbt.Def
import sbt.LocalProject
import sbt.Project
import sbt.ProjectRef
import sbt.State

import java.io.File

object MonorepoSpecSupport {

  def projectNamed(projects: Seq[ProjectReleaseInfo], name: String): ProjectReleaseInfo =
    projects.find(_.name == name).getOrElse(fail(s"Expected project '$name'"))

  def dummyContextResource(prefix: String): Resource[IO, MonorepoContext] =
    TestSupport.dummyStateResource(prefix).map(state => MonorepoContext(state = state))

  def loadedContextResource(
      prefix: String,
      selectedProjectIds: Seq[String],
      versionsById: Map[String, (String, String)] = Map.empty,
      vcs: Option[Vcs] = None,
      interactive: Boolean = false,
      skipTests: Boolean = false,
      skipPublish: Boolean = false
  )(projectsFor: File => Seq[Project]): Resource[IO, MonorepoContext] =
    loadedFixtureResource(prefix)(projectsFor).map(
      _.context(
        selectedProjectIds = selectedProjectIds,
        versionsById = versionsById,
        vcs = vcs,
        interactive = interactive,
        skipTests = skipTests,
        skipPublish = skipPublish
      )
    )

  def readNonEmptyLines(file: File): IO[List[String]] =
    IO.blocking {
      if (file.exists()) sbt.IO.readLines(file).filter(_.nonEmpty).toList
      else Nil
    }

  final case class LoadedFixture(
      dir: File,
      state: State,
      projects: Seq[Project],
      refsById: Map[String, ProjectRef]
  ) {

    def projectInfo(
        id: String,
        versions: Option[(String, String)] = None,
        tagName: Option[String] = None,
        failed: Boolean = false,
        failureCause: Option[Throwable] = None
    ): ProjectReleaseInfo =
      projects.find(_.id == id) match {
        case Some(project) =>
          ProjectReleaseInfo(
            ref = refsById.getOrElse(id, fail(s"Expected loaded ProjectRef for '$id'")),
            name = id,
            baseDir = project.base,
            versionFile = new File(project.base, "version.sbt"),
            versions = versions,
            tagName = tagName,
            failed = failed,
            failureCause = failureCause
          )
        case None          =>
          fail(s"Expected loaded project '$id'")
      }

    def context(
        selectedProjectIds: Seq[String],
        versionsById: Map[String, (String, String)] = Map.empty,
        vcs: Option[Vcs] = None,
        interactive: Boolean = false,
        skipTests: Boolean = false,
        skipPublish: Boolean = false
    ): MonorepoContext =
      MonorepoContext(
        state = state,
        vcs = vcs,
        projects = selectedProjectIds.map(id => projectInfo(id, versions = versionsById.get(id))),
        skipTests = skipTests,
        skipPublish = skipPublish,
        interactive = interactive
      )
  }

  val defaultFlags = ExecutionFlags(
    useDefaults = false,
    skipTests = false,
    skipPublish = false,
    interactive = false,
    crossBuild = false
  )

  def releasePlan(
      selectionMode: SelectionMode = SelectionMode.DetectChanges,
      flags: ExecutionFlags = defaultFlags,
      selectedNames: Seq[String] = Nil,
      releaseVersionOverrides: Map[String, String] = Map.empty,
      nextVersionOverrides: Map[String, String] = Map.empty,
      commandName: String = "releaseIOMonorepo"
  ): MonorepoReleasePlan =
    MonorepoReleasePlan(
      flags = flags,
      selectionMode = selectionMode,
      selectedNames = selectedNames,
      releaseVersionOverrides = releaseVersionOverrides,
      nextVersionOverrides = nextVersionOverrides,
      decisionDefaults = ReleaseDecisionDefaults.empty,
      commandName = commandName
    )

  def withPlan(ctx: MonorepoContext, plan: MonorepoReleasePlan): MonorepoContext =
    ctx.withReleasePlan(plan)

  def loadedFixtureResource(
      prefix: String
  )(projectsFor: File => Seq[Project]): Resource[IO, LoadedFixture] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val projects = projectsFor(dir)
        val state    = TestSupport.loadedState(
          dir,
          projects,
          currentProjectId = Some("root")
        )
        val refsById =
          SbtRuntime.extracted(state).structure.allProjectRefs.map(ref => ref.project -> ref).toMap
        LoadedFixture(dir = dir, state = state, projects = projects, refsById = refsById)
      }
    }

  def monorepoRootProject(
      repo: File,
      projectIds: Seq[String],
      settings: Seq[Def.Setting[?]] = Nil
  ): Project = {
    val aggregated =
      if (projectIds.nonEmpty)
        Project("root", repo).aggregate(projectIds.map(LocalProject(_))*)
      else Project("root", repo)

    aggregated.settings(
      (
        MonorepoReleaseIO.monorepoDefaultSettings ++
          Seq(
            io.release.ReleaseIO.releaseIOVersioningFile := new File(repo, "version.sbt"),
            io.release.ReleaseIO.releaseIOVcsSign        := false,
            io.release.ReleaseIO.releaseIOVcsSignOff     := false
          ) ++
          settings
      )*
    )
  }

  def versionedProject(
      id: String,
      base: File,
      settings: Seq[Def.Setting[?]] = Nil
  ): Project =
    Project(id, base).settings(
      (
        Seq(
          io.release.ReleaseIO.releaseIOVersioningFile := new File(base, "version.sbt")
        ) ++ settings
      )*
    )

  def requireProjectFailures(
      cause: Option[Throwable]
  ): MonorepoProjectFailures =
    cause match {
      case Some(aggregate: MonorepoProjectFailures) => aggregate
      case other                                    =>
        fail(s"Expected MonorepoProjectFailures but got $other")
    }
}
