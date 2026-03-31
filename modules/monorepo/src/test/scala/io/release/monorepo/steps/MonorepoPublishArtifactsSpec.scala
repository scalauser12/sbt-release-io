package io.release.monorepo.steps

import cats.effect.Resource
import io.release.ReleaseIO
import io.release.TestAssertions.assertIllegalStateMessage
import io.release.monorepo.MonorepoReleaseIO
import munit.CatsEffectSuite
import sbt.*
import sbt.Resolver
import sbt.Keys.*

import java.io.File

class MonorepoPublishArtifactsSpec extends CatsEffectSuite with MonorepoPublishStepsSpecSupport {

  test("publishArtifacts.validate - fail when checks are enabled and publishTo is empty") {
    singleProjectFixtureResource(
      "monorepo-publish-validate-fail",
      rootSettings = Seq(
        MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks := true
      )
    ) { _ =>
      Seq(
        publish / skip := false,
        publishTo      := None
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      assertIllegalStateMessage(
        MonorepoPublishSteps.publishArtifacts.validate(ctx, project),
        _root_.io.release.internal.PublishValidation.message("core")
      )
    }
  }

  test("publishArtifacts.validate - bypass checks when disabled or publish is globally skipped") {
    val checksDisabled = singleProjectFixtureResource(
      "monorepo-publish-validate-disabled",
      rootSettings = Seq(
        MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks := false
      )
    ) { _ =>
      Seq(
        publish / skip := false,
        publishTo      := None
      )
    }

    val skipPublish = singleProjectFixtureResource(
      "monorepo-publish-validate-skip",
      rootSettings = Seq(
        MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks := true
      )
    ) { _ =>
      Seq(
        publish / skip := false,
        publishTo      := None
      )
    }

    Resource.both(checksDisabled, skipPublish).use { case (disabledFixture, skippedFixture) =>
      val disabledCtx     = disabledFixture.context(Seq("core"))
      val disabledProject = disabledFixture.projectInfo("core")
      val skippedCtx      = skippedFixture.context(Seq("core"), skipPublish = true)
      val skippedProject  = skippedFixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.validate(disabledCtx, disabledProject) *>
        MonorepoPublishSteps.publishArtifacts.validate(skippedCtx, skippedProject)
    }
  }

  test("publishArtifacts.execute - skip the publish task when publish / skip is true") {
    singleProjectFixtureResource("monorepo-publish-skip-action") { _ =>
      Seq(
        publish / skip := true,
        ReleaseIO.releaseIOPublishArtifactsAction := {
          throw new RuntimeException("publish action should not run")
        }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.execute(ctx, project).map { _ =>
        assert(!new File(fixture.dir, "published.txt").exists())
      }
    }
  }

  test("publishArtifacts.execute - run the configured publish task when publish is enabled") {
    singleProjectFixtureResource("monorepo-publish-run-action") { projectBase =>
      val marker = new File(projectBase.getParentFile, "published.txt")

      Seq(
        publish / skip                          := false,
        publishTo                               := Some(Resolver.file("local-test", projectBase.getParentFile)),
        ReleaseIO.releaseIOPublishArtifactsAction := {
          sbt.IO.write(marker, "published")
        }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.execute(ctx, project).map { _ =>
        assert(new File(fixture.dir, "published.txt").exists())
      }
    }
  }

  test("publishArtifacts.validate - pass when publishTo is set and publish not skipped") {
    singleProjectFixtureResource(
      "monorepo-publish-validate-pass",
      rootSettings = Seq(
        MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks := true
      )
    ) { projectBase =>
      Seq(
        publish / skip := false,
        publishTo      := Some(Resolver.file("local-test", projectBase.getParentFile))
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
    }
  }
}
