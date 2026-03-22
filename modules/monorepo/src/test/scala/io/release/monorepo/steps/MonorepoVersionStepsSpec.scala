package io.release.monorepo.steps

import cats.effect.{IO, Ref}
import io.release.TestAssertions.assertFailure
import io.release.monorepo.{MonorepoContext, MonorepoReleaseIO, MonorepoSpecSupport, SelectionMode}
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.Project

import java.io.File

class MonorepoVersionStepsSpec extends CatsEffectSuite {

  test("inquireVersions.validate - fail when the resolved version file does not exist") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-missing-file") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport.versionedProject("core", coreBase)
        )
      }
      .use { fixture =>
        val ctx     = fixture.context(Seq("core"))
        val project = fixture.projectInfo("core")

        assertFailure[IllegalStateException, Unit](
          MonorepoVersionSteps.inquireVersions.validate(ctx, project)
        ) { err =>
          assert(err.getMessage.contains("Version file not found for core"))
          assert(err.getMessage.contains(project.versionFile.getPath))
        }
      }
  }

  test(
    "inquireVersions.execute - keep pre-set versions and refresh the resolved version file path"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-preset") { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()
        new File(dir, "versions").mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core"),
            settings = Seq(
              MonorepoReleaseIO.releaseIOMonorepoVersionFile := { (ref, _: sbt.State) =>
                new File(new File(dir, "versions"), s"${ref.project}.sbt")
              }
            )
          ),
          MonorepoSpecSupport.versionedProject("core", coreBase)
        )
      }
      .use { fixture =>
        val versions = "1.0.0" -> "1.1.0-SNAPSHOT"
        val ctx      = fixture.context(Seq("core"), versionsById = Map("core" -> versions))
        val project  = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

        MonorepoVersionSteps.inquireVersions.execute(ctx, project).map { result =>
          val updated = MonorepoSpecSupport.projectNamed(result.projects, "core")
          assertEquals(updated.versions, Some(versions))
          assertEquals(updated.versionFile, new File(new File(fixture.dir, "versions"), "core.sbt"))
        }
      }
  }

  test(
    "inquireVersions.execute - reuse the first project's versions in interactive global-version mode"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-global-reuse") { dir =>
        val coreBase = new File(dir, "core")
        val apiBase  = new File(dir, "api")
        coreBase.mkdirs()
        apiBase.mkdirs()
        sbt.IO.write(
          new File(dir, "version.sbt"),
          """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n"
        )

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core", "api"),
            settings = Seq(
              MonorepoReleaseIO.releaseIOMonorepoUseGlobalVersion := true
            )
          ),
          MonorepoSpecSupport.versionedProject("core", coreBase),
          MonorepoSpecSupport.versionedProject("api", apiBase)
        )
      }
      .use { fixture =>
        val ctx = MonorepoSpecSupport.withPlan(
          fixture.context(
            Seq("core", "api"),
            versionsById = Map("core" -> ("1.0.0" -> "1.1.0-SNAPSHOT")),
            interactive = true
          ),
          MonorepoSpecSupport.releasePlan(
            selectionMode = SelectionMode.AllChanged,
            flags = MonorepoSpecSupport.defaultFlags.copy(interactive = true)
          )
        )
        val api = MonorepoSpecSupport.projectNamed(ctx.projects, "api")

        MonorepoVersionSteps.inquireVersions.execute(ctx, api).map { result =>
          val updated = MonorepoSpecSupport.projectNamed(result.projects, "api")
          assertEquals(updated.versions, Some("1.0.0" -> "1.1.0-SNAPSHOT"))
          assertEquals(updated.versionFile, new File(fixture.dir, "version.sbt"))
        }
      }
  }

  test("validateVersions - succeed in global-version mode when all project versions agree") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-validate-ok") { dir =>
        val coreBase = new File(dir, "core")
        val apiBase  = new File(dir, "api")
        coreBase.mkdirs()
        apiBase.mkdirs()
        sbt.IO.write(
          new File(dir, "version.sbt"),
          """ThisBuild / version := "1.0.0-SNAPSHOT"""" + "\n"
        )

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core", "api"),
            settings = Seq(
              MonorepoReleaseIO.releaseIOMonorepoUseGlobalVersion := true
            )
          ),
          MonorepoSpecSupport.versionedProject("core", coreBase),
          MonorepoSpecSupport.versionedProject("api", apiBase)
        )
      }
      .use { fixture =>
        val ctx = fixture.context(
          Seq("core", "api"),
          versionsById = Map(
            "core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"),
            "api"  -> ("1.0.0" -> "1.1.0-SNAPSHOT")
          )
        )

        MonorepoVersionSteps.validateVersions.execute(ctx).map { result =>
          assertEquals(
            result.projects.map(_.versions).distinct,
            Seq(Some("1.0.0" -> "1.1.0-SNAPSHOT"))
          )
        }
      }
  }

  test("validateVersions - fail in global-version mode when project versions disagree") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-validate-fail") { dir =>
        val coreBase = new File(dir, "core")
        val apiBase  = new File(dir, "api")
        coreBase.mkdirs()
        apiBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core", "api"),
            settings = Seq(
              MonorepoReleaseIO.releaseIOMonorepoUseGlobalVersion := true
            )
          ),
          MonorepoSpecSupport.versionedProject("core", coreBase),
          MonorepoSpecSupport.versionedProject("api", apiBase)
        )
      }
      .use { fixture =>
        val ctx = fixture.context(
          Seq("core", "api"),
          versionsById = Map(
            "core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"),
            "api"  -> ("2.0.0" -> "2.1.0-SNAPSHOT")
          )
        )

        assertFailure[IllegalStateException, MonorepoContext](
          MonorepoVersionSteps.validateVersions.execute(ctx)
        ) { err =>
          assert(
            err.getMessage.contains(
              "Global version mode requires all projects to have the same release version"
            )
          )
          assert(err.getMessage.contains("core -> 1.0.0"))
          assert(err.getMessage.contains("api -> 2.0.0"))
        }
      }
  }

  test("setReleaseVersions - write the shared global version file only once") {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-write-global") { dir =>
        val coreBase = new File(dir, "core")
        val apiBase  = new File(dir, "api")
        coreBase.mkdirs()
        apiBase.mkdirs()
        sbt.IO.write(
          new File(dir, "version.sbt"),
          """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n"
        )

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core", "api"),
            settings = {
              val counterFile    = new File(dir, "write-count.txt")
              Seq(
                MonorepoReleaseIO.releaseIOMonorepoUseGlobalVersion    := true,
                MonorepoReleaseIO.releaseIOMonorepoVersionFileContents := (
                  (_: File, version: String) =>
                    IO.blocking {
                      sbt.IO.append(counterFile, s"$version\n")
                      s"""ThisBuild / version := "$version"\n"""
                    }
                )
              )
            }
          ),
          MonorepoSpecSupport.versionedProject("core", coreBase),
          MonorepoSpecSupport.versionedProject("api", apiBase)
        )
      }
      .use { fixture =>
        val ctx  = MonorepoSpecSupport.withPlan(
          fixture.context(
            Seq("core", "api"),
            versionsById = Map(
              "core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"),
              "api"  -> ("1.0.0" -> "1.1.0-SNAPSHOT")
            )
          ),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.AllChanged)
        )
        val core = MonorepoSpecSupport.projectNamed(ctx.projects, "core")
        val api  = MonorepoSpecSupport.projectNamed(ctx.projects, "api")

        for {
          afterCore <- MonorepoVersionSteps.setReleaseVersions.execute(ctx, core)
          afterApi  <- MonorepoVersionSteps.setReleaseVersions.execute(
                         afterCore,
                         MonorepoSpecSupport.projectNamed(afterCore.projects, "api")
                       )
          writes    <- MonorepoSpecSupport.readNonEmptyLines(new File(fixture.dir, "write-count.txt"))
          contents  <- IO.blocking(sbt.IO.read(new File(fixture.dir, "version.sbt")))
        } yield {
          assertEquals(writes, List("1.0.0"))
          assertEquals(afterApi.globalVersionWritten, Some("1.0.0"))
          assert(contents.contains("""ThisBuild / version := "1.0.0""""))
          assertEquals(
            MonorepoSpecSupport.projectNamed(afterApi.projects, "api").versionFile,
            new File(new File(fixture.dir, "api"), "version.sbt")
          )
        }
      }
  }

  test(
    "commitReleaseVersions - stage and commit the shared version file only once in global mode"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-version-commit-global") { dir =>
        val coreBase = new File(dir, "core")
        val apiBase  = new File(dir, "api")
        coreBase.mkdirs()
        apiBase.mkdirs()
        sbt.IO.write(new File(dir, "version.sbt"), """ThisBuild / version := "1.0.0"""" + "\n")

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core", "api"),
            settings = Seq(
              MonorepoReleaseIO.releaseIOMonorepoUseGlobalVersion := true,
              io.release.ReleaseIO.releaseIOVcsSign               := false,
              io.release.ReleaseIO.releaseIOVcsSignOff            := false
            )
          ),
          MonorepoSpecSupport.versionedProject("core", coreBase),
          MonorepoSpecSupport.versionedProject("api", apiBase)
        )
      }
      .use { fixture =>
        for {
          added     <- Ref[IO].of(List.empty[Seq[String]])
          commits   <- Ref[IO].of(List.empty[(String, Boolean, Boolean)])
          ctx        = fixture.context(
                         Seq("core", "api"),
                         versionsById = Map(
                           "core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"),
                           "api"  -> ("1.0.0" -> "1.1.0-SNAPSHOT")
                         ),
                         vcs = Some(
                           new RecordingVcs(
                             baseDir = fixture.dir,
                             addCalls = added,
                             commitCalls = commits,
                             status0 = IO.pure("M version.sbt")
                           )
                         )
                       )
          result    <- MonorepoVersionSteps.commitReleaseVersions.execute(ctx)
          addObs    <- added.get
          commitObs <- commits.get
        } yield {
          assertEquals(addObs, List(Seq("version.sbt")))
          assertEquals(commitObs.length, 1)
          assertEquals(commitObs.head._1, "Setting release versions: core 1.0.0, api 1.0.0")
          assertEquals(result.vcs.map(_.commandName), Some("stub"))
        }
      }
  }

  private final class RecordingVcs(
      val baseDir: File,
      addCalls: Ref[IO, List[Seq[String]]],
      commitCalls: Ref[IO, List[(String, Boolean, Boolean)]],
      status0: IO[String]
  ) extends Vcs {
    override def commandName: String = "stub"

    override def currentHash: IO[String] = IO.pure("deadbeef")

    override def currentBranch: IO[String] = IO.pure("main")

    override def trackingRemote: IO[String] = IO.pure("origin")

    override def hasUpstream: IO[Boolean] = IO.pure(true)

    override def isBehindRemote: IO[Boolean] = IO.pure(false)

    override def existsTag(name: String): IO[Boolean] = IO.pure(false)

    override def modifiedFiles: IO[Seq[String]] = IO.pure(Seq("version.sbt"))

    override def stagedFiles: IO[Seq[String]] = IO.pure(Seq.empty)

    override def untrackedFiles: IO[Seq[String]] = IO.pure(Seq.empty)

    override def status: IO[String] = status0

    override def checkRemote(remote: String): IO[Int] = IO.pure(0)

    override def add(files: String*): IO[Unit] =
      addCalls.update(_ :+ files)

    override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit] =
      commitCalls.update(_ :+ ((message, sign, signOff)))

    override def tag(name: String, comment: String, sign: Boolean, force: Boolean): IO[Unit] =
      IO.unit

    override def pushChanges: IO[Unit] = IO.unit
  }
}
