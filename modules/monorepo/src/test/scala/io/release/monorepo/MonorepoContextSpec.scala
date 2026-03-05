package io.release.monorepo

import io.release.TestSupport
import org.specs2.mutable.Specification
import sbt.State

import java.nio.file.Files

class MonorepoContextSpec extends Specification {

  "MonorepoContext" should {

    "update a specific project" in withState { state =>
      val projects = Seq(dummyProject("core"), dummyProject("api"))
      val ctx      = MonorepoContext(state = state, projects = projects)
      val updated  =
        ctx.updateProject(projects(0).ref)(_.copy(versions = Some(("1.0.0", "1.1.0-SNAPSHOT"))))

      (updated.projects(0).versions must beSome(("1.0.0", "1.1.0-SNAPSHOT"))) and
        (updated.projects(1).versions must beNone)
    }

    "filter out failed projects in currentProjects" in withState { state =>
      val projects = Seq(
        dummyProject("core").copy(failed = true),
        dummyProject("api")
      )
      val ctx      = MonorepoContext(state = state, projects = projects)

      ctx.currentProjects.map(_.name) must_== Seq("api")
    }

    "manage attributes" in withState { state =>
      val ctx     = MonorepoContext(state = state)
      val updated = ctx.withAttr("key1", "val1").withAttr("key2", "val2")

      (updated.attr("key1") must beSome("val1")) and
        (updated.attr("key2") must beSome("val2")) and
        (updated.attr("missing") must beNone)
    }

    "mark as failed" in withState { state =>
      val ctx = MonorepoContext(state = state)
      (ctx.failed must_== false) and
        (ctx.fail.failed must_== true)
    }

    "replace projects via withProjects" in withState { state =>
      val ctx     = MonorepoContext(state = state, projects = Seq(dummyProject("old")))
      val updated = ctx.withProjects(Seq(dummyProject("new1"), dummyProject("new2")))

      updated.projects.map(_.name) must_== Seq("new1", "new2")
    }
  }

  "ProjectReleaseInfo" should {

    "have sensible defaults" in {
      val proj = dummyProject("test")
      (proj.versions must beNone) and
        (proj.tagName must beNone) and
        (proj.failed must_== false)
    }
  }

  "MonorepoTagStrategy" should {

    "have PerProject and Unified variants" in {
      val pp: MonorepoTagStrategy = MonorepoTagStrategy.PerProject
      val u: MonorepoTagStrategy  = MonorepoTagStrategy.Unified
      pp must not(equalTo(u))
    }
  }

  private def dummyProject(name: String): ProjectReleaseInfo =
    ProjectReleaseInfo(
      ref = sbt.ProjectRef(new java.net.URI("file:///tmp/test"), name),
      name = name,
      baseDir = new java.io.File(s"/tmp/test/$name"),
      versionFile = new java.io.File(s"/tmp/test/$name/version.sbt")
    )

  private def withState[A](f: State => A): A = {
    val dir = Files.createTempDirectory("monorepo-ctx-spec").toFile
    try f(TestSupport.dummyState(dir))
    finally TestSupport.deleteRecursively(dir)
  }
}
