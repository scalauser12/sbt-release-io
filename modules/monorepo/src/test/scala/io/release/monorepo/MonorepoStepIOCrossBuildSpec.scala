package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import io.release.internal.ProcessStep
import io.release.monorepo.steps.MonorepoPublishSteps
import io.release.monorepo.steps.MonorepoStepTestCompat
import munit.CatsEffectSuite
import sbt.AttributeKey
import sbt.LocalProject
import sbt.Project
import sbt.Keys.*

import java.io.File

@scala.annotation.nowarn("cat=deprecation")
class MonorepoStepIOCrossBuildSpec extends CatsEffectSuite with MonorepoStepIOSpecSupport {

  test(
    "compose - cross-build single-version per-project step validates and executes once and restores the entry scalaVersion"
  ) {
    loadedContextResource("monorepo-step-single-cross", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(TestSupport.alternateScalaVersion)
        )
      )
    }.use { ctx =>
      val step = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "cross-step",
        validate = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "validate.txt"), c.state),
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "execute.txt"), c.state).as(c),
        enableCrossBuild = true
      )

      MonorepoComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
        val project = MonorepoSpecSupport.projectNamed(result.projects, "core")

        for {
          restoredVersion <- scalaVersionOf(result.state)
          validateLines   <-
            MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "validate.txt"))
          executeLines    <-
            MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "execute.txt"))
        } yield {
          assertEquals(validateLines, List(TestSupport.alternateScalaVersion))
          assertEquals(executeLines, List(TestSupport.alternateScalaVersion))
          assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
        }
      }
    }
  }

  test("compose - restore the selected project's entry scalaVersion when currentRef differs") {
    loadedContextResource("monorepo-step-project-entry-version", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.alternateScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(TestSupport.alternateScalaVersion)
        )
      )
    }.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val step = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
          name = "cross-step",
          execute = (c, project) =>
            projectScalaVersionOf(c.state, project.ref).flatMap {
              case Some(version) => observed.update(_ :+ version).as(c)
              case None          =>
                IO.raiseError(
                  new RuntimeException("missing project scalaVersion during cross-build")
                )
            },
          enableCrossBuild = true
        )

        val project = MonorepoSpecSupport.projectNamed(ctx.projects, "core")

        for {
          initialRootVersion    <- scopedScalaVersionOf(ctx.state)
          initialProjectVersion <- projectScalaVersionOf(ctx.state, project.ref)
          result                <- MonorepoComposer.compose(Seq(step), crossBuild = true)(ctx)
          observedVersions      <- observed.get
          restoredProject       <- projectScalaVersionOf(result.state, project.ref)
        } yield {
          assertEquals(initialRootVersion, Some(TestSupport.alternateScalaVersion))
          assertEquals(initialProjectVersion, Some(TestSupport.CurrentScalaVersion))
          assertEquals(observedVersions, List(TestSupport.alternateScalaVersion))
          assertEquals(restoredProject, Some(TestSupport.CurrentScalaVersion))
        }
      }
    }
  }

  test(
    "compose - cross-build multi-version per-project steps run once per configured version and later non-cross steps still run once"
  ) {
    loadedContextResource("monorepo-step-multi-cross", Seq("core", "api")) { dir =>
      val coreBase = new File(dir, "core")
      val apiBase  = new File(dir, "api")
      coreBase.mkdirs()
      apiBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"), LocalProject("api"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        ),
        Project("api", apiBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(TestSupport.CurrentScalaVersion)
        )
      )
    }.use { ctx =>
      val crossStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "cross-step",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "cross-invocations.txt"), c.state)
            .as(c),
        enableCrossBuild = true
      )
      val plainStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "plain-step",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "plain-invocations.txt"), c.state)
            .as(c)
      )

      MonorepoComposer.compose(Seq(crossStep, plainStep), crossBuild = true)(ctx).flatMap {
        result =>
          val core = MonorepoSpecSupport.projectNamed(result.projects, "core")
          val api  = MonorepoSpecSupport.projectNamed(result.projects, "api")

          for {
            restoredVersion <- scalaVersionOf(result.state)
            coreCross       <-
              MonorepoSpecSupport.readNonEmptyLines(new File(core.baseDir, "cross-invocations.txt"))
            apiCross        <-
              MonorepoSpecSupport.readNonEmptyLines(new File(api.baseDir, "cross-invocations.txt"))
            corePlain       <-
              MonorepoSpecSupport.readNonEmptyLines(new File(core.baseDir, "plain-invocations.txt"))
            apiPlain        <-
              MonorepoSpecSupport.readNonEmptyLines(new File(api.baseDir, "plain-invocations.txt"))
          } yield {
            assertEquals(
              coreCross,
              List(TestSupport.CurrentScalaVersion, TestSupport.alternateScalaVersion)
            )
            assertEquals(apiCross, List(TestSupport.CurrentScalaVersion))
            assertEquals(corePlain, List(TestSupport.CurrentScalaVersion))
            assertEquals(apiPlain, List(TestSupport.CurrentScalaVersion))
            assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
          }
      }
    }
  }

  test(
    "compose - per-project cross-build deduplicates configured Scala versions and restores entry state for later steps"
  ) {
    loadedContextResource("monorepo-step-dedup-cross", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion,
            TestSupport.CurrentScalaVersion
          )
        )
      )
    }.use { ctx =>
      val crossStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "cross-step",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "cross-invocations.txt"), c.state)
            .as(c),
        enableCrossBuild = true
      )
      val plainStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "plain-step",
        execute = (c, project) =>
          projectScalaVersionOf(c.state, project.ref).flatMap {
            case Some(version) =>
              IO.blocking {
                sbt.IO.append(new File(project.baseDir, "plain-project-version.txt"), s"$version\n")
              }.as(c)
            case None          =>
              IO.raiseError(
                new RuntimeException(s"missing restored scalaVersion for ${project.name}")
              )
          }
      )

      MonorepoComposer.compose(Seq(crossStep, plainStep), crossBuild = true)(ctx).flatMap {
        result =>
          val core = MonorepoSpecSupport.projectNamed(result.projects, "core")

          for {
            restoredVersion  <- scalaVersionOf(result.state)
            restoredCore     <- projectScalaVersionOf(result.state, core.ref)
            crossInvocations <-
              MonorepoSpecSupport.readNonEmptyLines(new File(core.baseDir, "cross-invocations.txt"))
            plainVersions    <- MonorepoSpecSupport.readNonEmptyLines(
                                  new File(core.baseDir, "plain-project-version.txt")
                                )
          } yield {
            assertEquals(
              crossInvocations,
              List(TestSupport.CurrentScalaVersion, TestSupport.alternateScalaVersion)
            )
            assertEquals(plainVersions, List(TestSupport.CurrentScalaVersion))
            assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
            assertEquals(restoredCore, Some(TestSupport.CurrentScalaVersion))
          }
      }
    }
  }

  test(
    "compose - heterogeneous project entry scalaVersions are restored before later plain per-project steps"
  ) {
    loadedContextResource("monorepo-step-heterogeneous-restore", Seq("core", "api")) { dir =>
      val coreBase = new File(dir, "core")
      val apiBase  = new File(dir, "api")
      coreBase.mkdirs()
      apiBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"), LocalProject("api"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        ),
        Project("api", apiBase).settings(
          scalaVersion           := TestSupport.alternateScalaVersion,
          crossScalaVersions     := Seq(TestSupport.alternateScalaVersion)
        )
      )
    }.use { ctx =>
      val crossStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "cross-step",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "cross-invocations.txt"), c.state)
            .as(c),
        enableCrossBuild = true
      )
      val plainStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "plain-step",
        execute = (c, project) =>
          projectScalaVersionOf(c.state, project.ref).flatMap {
            case Some(version) =>
              IO.blocking {
                sbt.IO.append(
                  new File(project.baseDir, "plain-project-version.txt"),
                  s"$version\n"
                )
              }.as(c)
            case None          =>
              IO.raiseError(
                new RuntimeException(s"missing restored scalaVersion for ${project.name}")
              )
          }
      )

      MonorepoComposer.compose(Seq(crossStep, plainStep), crossBuild = true)(ctx).flatMap {
        result =>
          val core = MonorepoSpecSupport.projectNamed(result.projects, "core")
          val api  = MonorepoSpecSupport.projectNamed(result.projects, "api")

          for {
            restoredVersion <- scalaVersionOf(result.state)
            restoredCore    <- projectScalaVersionOf(result.state, core.ref)
            restoredApi     <- projectScalaVersionOf(result.state, api.ref)
            corePlain       <- MonorepoSpecSupport.readNonEmptyLines(
                                 new File(core.baseDir, "plain-project-version.txt")
                               )
            apiPlain        <- MonorepoSpecSupport.readNonEmptyLines(
                                 new File(api.baseDir, "plain-project-version.txt")
                               )
          } yield {
            assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
            assertEquals(restoredCore, Some(TestSupport.CurrentScalaVersion))
            assertEquals(restoredApi, Some(TestSupport.alternateScalaVersion))
            assertEquals(corePlain, List(TestSupport.CurrentScalaVersion))
            assertEquals(apiPlain, List(TestSupport.alternateScalaVersion))
          }
      }
    }
  }

  test("compose - restore the entry state when no entry scalaVersion is defined") {
    val metadataKey = AttributeKey[String]("cross-build-no-entry-scala-version")

    loadedContextResource("monorepo-step-no-entry-scala-version", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir).aggregate(LocalProject("core")),
        Project("core", coreBase).settings(
          crossScalaVersions := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        )
      )
    }.use { ctx =>
      val step = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "cross-step",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "no-entry.txt"), c.state)
            .as(c.withMetadata(metadataKey, "kept")),
        enableCrossBuild = true
      )

      scopedScalaVersionOf(ctx.state).flatMap { initialVersion =>
        MonorepoComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
          val project = MonorepoSpecSupport.projectNamed(result.projects, "core")

          for {
            restoredVersion <- scopedScalaVersionOf(result.state)
            versions        <-
              MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "no-entry.txt"))
          } yield {
            assertEquals(initialVersion, None)
            assertEquals(restoredVersion, None)
            assertEquals(
              versions,
              List(TestSupport.CurrentScalaVersion, TestSupport.alternateScalaVersion)
            )
            assertEquals(result.metadata(metadataKey), Some("kept"))
          }
        }
      }
    }
  }

  test("compose - fail validation when cross-build is enabled with empty crossScalaVersions") {
    loadedContextResource("monorepo-step-empty-cross", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq.empty
        )
      )
    }.use { ctx =>
      val marker = new File(
        MonorepoSpecSupport.projectNamed(ctx.projects, "core").baseDir,
        "should-not-run.txt"
      )
      val step   = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "cross-step",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "should-not-run.txt"), c.state).as(c),
        enableCrossBuild = true
      )

      assertFailure[IllegalStateException, MonorepoContext](
        MonorepoComposer.compose(Seq(step), crossBuild = true)(ctx)
      ) { err =>
        assert(err.getMessage.contains("Cross-build enabled but core has empty crossScalaVersions"))
        assert(!marker.exists())
      }
    }
  }

  test("compose - cross-build execute uses the latest project snapshot on later versions") {
    loadedContextResource("monorepo-step-cross-execute-latest-project", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        )
      )
    }.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val step = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
          name = "cross-step",
          execute = (c, project) =>
            scalaVersionOf(c.state).flatMap { version =>
              observed.update(_ :+ s"$version:${project.tagName.getOrElse("missing")}") *>
                (if (version == TestSupport.CurrentScalaVersion)
                   IO.pure(c.updateProject(project.ref)(_.copy(tagName = Some("updated"))))
                 else
                   IO.pure(c))
            },
          enableCrossBuild = true
        )

        MonorepoComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
          observed.get.map { obs =>
            val project = MonorepoSpecSupport.projectNamed(result.projects, "core")
            assertEquals(
              obs,
              List(
                s"${TestSupport.CurrentScalaVersion}:missing",
                s"${TestSupport.alternateScalaVersion}:updated"
              )
            )
            assertEquals(project.tagName, Some("updated"))
          }
        }
      }
    }
  }

  test("compose - cross-build validation uses the latest project snapshot on later versions") {
    loadedContextResource("monorepo-step-cross-validate-latest-project", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        )
      )
    }.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val step = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
          name = "cross-step",
          execute = (c, _) => IO.pure(c),
          validateWithContext = Some((c, project) =>
            scalaVersionOf(c.state).flatMap { version =>
              observed.update(_ :+ s"$version:${project.tagName.getOrElse("missing")}") *>
                (if (version == TestSupport.CurrentScalaVersion)
                   IO.pure(c.updateProject(project.ref)(_.copy(tagName = Some("validated"))))
                 else
                   IO.pure(c))
            }
          ),
          enableCrossBuild = true
        )

        MonorepoComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
          observed.get.map { obs =>
            val project = MonorepoSpecSupport.projectNamed(result.projects, "core")
            assertEquals(
              obs,
              List(
                s"${TestSupport.CurrentScalaVersion}:missing",
                s"${TestSupport.alternateScalaVersion}:validated"
              )
            )
            assertEquals(project.tagName, Some("validated"))
          }
        }
      }
    }
  }

  test("compose - per-project validation returning ctx.failWith stops later projects") {
    contextResource.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val pCtx = ctx.withProjects(Seq(dummyProject("core"), dummyProject("api")))
        val step = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
          name = "validate-fail-with-step",
          execute = (c, _) => IO.pure(c),
          validateWithContext = Some((c, project) =>
            observed.update(_ :+ project.name).as {
              if (project.name == "core")
                c.failWith(new RuntimeException("fatal stop"))
              else c
            }
          )
        )

        MonorepoComposer.compose(Seq(step))(pCtx).flatMap { result =>
          observed.get.map { obs =>
            assert(result.failed)
            assertEquals(obs, List("core"))
          }
        }
      }
    }
  }

  test(
    "compose - cross-build validation short-circuits later projects when the first project returns ctx.failWith"
  ) {
    loadedContextResource("monorepo-step-cross-validation-short-circuit", Seq("core", "api")) {
      dir =>
        val coreBase = new File(dir, "core")
        val apiBase  = new File(dir, "api")
        coreBase.mkdirs()
        apiBase.mkdirs()

        Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"), LocalProject("api"))
            .settings(scalaVersion := TestSupport.CurrentScalaVersion),
          Project("core", coreBase).settings(
            scalaVersion           := TestSupport.CurrentScalaVersion,
            crossScalaVersions     := Seq(TestSupport.CurrentScalaVersion)
          ),
          Project("api", apiBase).settings(
            scalaVersion           := TestSupport.CurrentScalaVersion,
            crossScalaVersions     := Seq(TestSupport.CurrentScalaVersion)
          )
        )
    }.use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val step = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
          name = "cross-validate-fail-with-step",
          execute = (c, _) => IO.pure(c),
          validateWithContext = Some((c, project) =>
            observed.update(_ :+ project.name).as {
              if (project.name == "core")
                c.failWith(new RuntimeException("fatal stop"))
              else c
            }
          ),
          enableCrossBuild = true
        )

        MonorepoComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
          scalaVersionOf(result.state).flatMap { restoredVersion =>
            observed.get.map { obs =>
              assert(result.failed)
              assertEquals(obs, List("core"))
              assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
            }
          }
        }
      }
    }
  }

  test("compose - restore the entry scalaVersion after a cross-build iteration fails") {
    val metadataKey = AttributeKey[String]("cross-build-metadata-marker")

    loadedContextResource("monorepo-step-cross-failure", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        )
      )
    }.use { ctx =>
      val step = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "cross-step",
        execute = (c, project) =>
          scalaVersionOf(c.state).flatMap { version =>
            appendCurrentScalaVersion(new File(project.baseDir, "cross-failure.txt"), c.state) *>
              (if (version == TestSupport.alternateScalaVersion)
                 if (c.metadata(metadataKey).contains("updated"))
                   IO.raiseError(new RuntimeException("boom"))
                 else
                   IO.raiseError(new RuntimeException("missing metadata before failure"))
               else
                 IO.pure(c.withMetadata(metadataKey, "updated")))
          },
        enableCrossBuild = true
      )

      MonorepoComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
        val project = MonorepoSpecSupport.projectNamed(result.projects, "core")

        for {
          restoredVersion <- scalaVersionOf(result.state)
          failureLines    <-
            MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "cross-failure.txt"))
        } yield {
          assert(result.failed)
          assert(project.failed)
          assertEquals(
            failureLines,
            List(TestSupport.CurrentScalaVersion, TestSupport.alternateScalaVersion)
          )
          assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
          val aggregate = requireProjectFailures(result.failureCause)
          assertEquals(
            aggregate.failures.find(_.projectName == "core").flatMap(_.cause).map(_.getMessage),
            Some("boom")
          )
        }
      }
    }
  }

  test(
    "compose - cross-build short-circuits remaining versions when a version fails via context"
  ) {
    loadedContextResource("monorepo-step-cross-short-circuit", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        )
      )
    }.use { ctx =>
      val step = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "cross-step",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "short-circuit.txt"), c.state)
            .as(c.failWith(new IllegalStateException("simulated task failure"))),
        enableCrossBuild = true
      )

      MonorepoComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
        val project = MonorepoSpecSupport.projectNamed(result.projects, "core")

        MonorepoSpecSupport
          .readNonEmptyLines(new File(project.baseDir, "short-circuit.txt"))
          .map { lines =>
            assert(result.failed)
            assertEquals(lines, List(TestSupport.CurrentScalaVersion))
          }
      }
    }
  }

  test("compose - cross-build short-circuit restores entry Scala version") {
    loadedContextResource("monorepo-step-cross-short-circuit-restore", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.alternateScalaVersion,
            TestSupport.CurrentScalaVersion
          )
        )
      )
    }.use { ctx =>
      val step = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "cross-step",
        execute = (c, _) => IO.pure(c.failWith(new IllegalStateException("fail on first version"))),
        enableCrossBuild = true
      )

      MonorepoComposer.compose(Seq(step), crossBuild = true)(ctx).flatMap { result =>
        scalaVersionOf(result.state).map { finalVersion =>
          assert(result.failed)
          assertEquals(finalVersion, TestSupport.CurrentScalaVersion)
        }
      }
    }
  }

  test(
    "compose - cross-build short-circuits remaining versions when a project is marked failed via FailureCommand"
  ) {
    loadedContextResource("monorepo-step-cross-failure-command", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      val marker   = new File(coreBase, "test-ran.txt")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          ),
          MonorepoStepTestCompat.failureCommandTestTaskSetting(marker)
        )
      )
    }.use { ctx =>
      MonorepoComposer
        .compose(Seq(MonorepoPublishSteps.runTests), crossBuild = true)(ctx)
        .flatMap { result =>
          scalaVersionOf(result.state).map { finalVersion =>
            val core      = MonorepoSpecSupport.projectNamed(result.projects, "core")
            assert(result.failed, "expected global failure after propagation")
            assert(core.failed, "expected project-level failure")
            assertEquals(finalVersion, TestSupport.CurrentScalaVersion)
            val aggregate = requireProjectFailures(result.failureCause)
            assertEquals(aggregate.failures.map(_.projectName), Seq("core"))
          }
        }
    }
  }

  test("compose - cross validation runs per-version while non-cross validation runs once") {
    loadedContextResource("monorepo-step-cross-validation-mix", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        )
      )
    }.use { ctx =>
      val crossStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "cross-step",
        validate = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "cross-validate.txt"), c.state),
        execute = (c, _) => IO.pure(c),
        enableCrossBuild = true
      )
      val plainStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "plain-step",
        validate = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "plain-validate.txt"), c.state),
        execute = (c, _) => IO.pure(c)
      )

      MonorepoComposer.compose(Seq(crossStep, plainStep), crossBuild = true)(ctx).flatMap {
        result =>
          val project = MonorepoSpecSupport.projectNamed(result.projects, "core")

          for {
            crossLines <-
              MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "cross-validate.txt"))
            plainLines <-
              MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "plain-validate.txt"))
          } yield {
            assertEquals(
              crossLines,
              List(TestSupport.CurrentScalaVersion, TestSupport.alternateScalaVersion)
            )
            assertEquals(plainLines, List(TestSupport.CurrentScalaVersion))
          }
      }
    }
  }

  test("compose - two sequential cross-build steps each iterate all versions independently") {
    loadedContextResource("monorepo-step-sequential-cross", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        )
      )
    }.use { ctx =>
      val step1 = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "cross-step-1",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "step1.txt"), c.state).as(c),
        enableCrossBuild = true
      )
      val step2 = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "cross-step-2",
        execute = (c, project) =>
          appendCurrentScalaVersion(new File(project.baseDir, "step2.txt"), c.state).as(c),
        enableCrossBuild = true
      )

      MonorepoComposer.compose(Seq(step1, step2), crossBuild = true)(ctx).flatMap { result =>
        val project = MonorepoSpecSupport.projectNamed(result.projects, "core")

        for {
          restoredVersion <- scalaVersionOf(result.state)
          step1Lines      <-
            MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "step1.txt"))
          step2Lines      <-
            MonorepoSpecSupport.readNonEmptyLines(new File(project.baseDir, "step2.txt"))
        } yield {
          assertEquals(
            step1Lines,
            List(TestSupport.CurrentScalaVersion, TestSupport.alternateScalaVersion)
          )
          assertEquals(
            step2Lines,
            List(TestSupport.CurrentScalaVersion, TestSupport.alternateScalaVersion)
          )
          assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
        }
      }
    }
  }
}
