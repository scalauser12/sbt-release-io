package io.release.monorepo

import cats.effect.IO
import io.release.TestSupport
import io.release.monorepo.internal.*
import io.release.monorepo.MonorepoHookConfigurationSpec.GlobalHookField
import io.release.monorepo.MonorepoHookConfigurationSpec.PolicyField
import io.release.monorepo.MonorepoHookConfigurationSpec.ProjectHookField
import munit.CatsEffectSuite
import sbt.*

class MonorepoHookConfigurationSpec extends CatsEffectSuite {

  test("defaultSettings - expose each lifecycle-derived setting key exactly once") {
    IO {
      val labels =
        MonorepoHookConfiguration.defaultSettings.map(_.key.key.label)

      assertEquals(labels, labels.distinct)
      // 6 policy + 19 hook = 25 settings
      assertEquals(labels.size, 25)
    }
  }

  test("field coverage - update tables when MonorepoHookConfiguration fields change") {
    IO {
      assertEquals(
        MonorepoHookConfiguration.empty.productArity,
        policyFields.size + globalHookFields.size + projectHookFields.size
      )
    }
  }

  test("resolve - read lifecycle policies and hook buckets from state") {
    stateResource(
      "monorepo-hook-configuration-resolve",
      Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePublish        := false,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection       := Seq(
          MonorepoGlobalHookIO.sideEffect("before-selection")(_ => IO.unit)
        ),
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextVersionWrite := Seq(
          MonorepoProjectHookIO.sideEffect("after-next-version")((_, _) => IO.unit)
        )
      )
    ).use { state =>
      IO {
        val config = MonorepoHookConfiguration.resolve(state)

        assert(!config.enablePublish)
        assertEquals(config.beforeSelectionHooks.map(_.name), Seq("before-selection"))
        assertEquals(
          config.afterNextVersionWriteHooks.map(_.name),
          Seq("after-next-version")
        )
      }
    }
  }

  test("merge - combine every policy with logical and and append every hook bucket in order") {
    IO {
      val left   = projectHookFields
        .foldLeft(globalHookFields.foldLeft(MonorepoHookConfiguration.empty) { (config, field) =>
          field.withHooks(
            config,
            Seq(MonorepoGlobalHookIO.sideEffect(s"${field.name}-left")(_ => IO.unit))
          )
        }) { (config, field) =>
          field.withHooks(
            config,
            Seq(MonorepoProjectHookIO.sideEffect(s"${field.name}-left")((_, _) => IO.unit))
          )
        }
      val right  = projectHookFields
        .foldLeft(
          globalHookFields.foldLeft(
            policyFields.foldLeft(MonorepoHookConfiguration.empty) { (config, field) =>
              field.withValue(config, false)
            }
          ) { (config, field) =>
            field.withHooks(
              config,
              Seq(MonorepoGlobalHookIO.sideEffect(s"${field.name}-right")(_ => IO.unit))
            )
          }
        ) { (config, field) =>
          field.withHooks(
            config,
            Seq(MonorepoProjectHookIO.sideEffect(s"${field.name}-right")((_, _) => IO.unit))
          )
        }
      val merged = MonorepoHookConfiguration.merge(left, right)

      policyFields.foreach { field =>
        if (field.accessor(merged))
          fail(s"Expected merged policy '${field.name}' to be false")
      }
      globalHookFields.foreach { field =>
        val expected = Seq(s"${field.name}-left", s"${field.name}-right")
        val actual   = field.namesOf(merged)
        if (actual != expected)
          fail(
            s"Expected merged global hook bucket '${field.name}' to preserve order: " +
              s"$expected, got $actual"
          )
      }
      projectHookFields.foreach { field =>
        val expected = Seq(s"${field.name}-left", s"${field.name}-right")
        val actual   = field.namesOf(merged)
        if (actual != expected)
          fail(
            s"Expected merged project hook bucket '${field.name}' to preserve order: " +
              s"$expected, got $actual"
          )
      }
    }
  }

  private val policyFields = Seq(
    PolicyField(
      "enableSnapshotDependenciesCheck",
      _.enableSnapshotDependenciesCheck,
      (config, value) => config.copy(enableSnapshotDependenciesCheck = value)
    ),
    PolicyField(
      "enableRunClean",
      _.enableRunClean,
      (config, value) => config.copy(enableRunClean = value)
    ),
    PolicyField(
      "enableRunTests",
      _.enableRunTests,
      (config, value) => config.copy(enableRunTests = value)
    ),
    PolicyField(
      "enableTagging",
      _.enableTagging,
      (config, value) => config.copy(enableTagging = value)
    ),
    PolicyField(
      "enablePublish",
      _.enablePublish,
      (config, value) => config.copy(enablePublish = value)
    ),
    PolicyField("enablePush", _.enablePush, (config, value) => config.copy(enablePush = value))
  )

  private val globalHookFields = Seq(
    GlobalHookField(
      "afterCleanCheckHooks",
      _.afterCleanCheckHooks.map(_.name),
      (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    ),
    GlobalHookField(
      "beforeSelectionHooks",
      _.beforeSelectionHooks.map(_.name),
      (config, hooks) => config.copy(beforeSelectionHooks = hooks)
    ),
    GlobalHookField(
      "afterSelectionHooks",
      _.afterSelectionHooks.map(_.name),
      (config, hooks) => config.copy(afterSelectionHooks = hooks)
    ),
    GlobalHookField(
      "beforeReleaseCommitHooks",
      _.beforeReleaseCommitHooks.map(_.name),
      (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    ),
    GlobalHookField(
      "afterReleaseCommitHooks",
      _.afterReleaseCommitHooks.map(_.name),
      (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    ),
    GlobalHookField(
      "beforeNextCommitHooks",
      _.beforeNextCommitHooks.map(_.name),
      (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    ),
    GlobalHookField(
      "afterNextCommitHooks",
      _.afterNextCommitHooks.map(_.name),
      (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    ),
    GlobalHookField(
      "beforePushHooks",
      _.beforePushHooks.map(_.name),
      (config, hooks) => config.copy(beforePushHooks = hooks)
    ),
    GlobalHookField(
      "afterPushHooks",
      _.afterPushHooks.map(_.name),
      (config, hooks) => config.copy(afterPushHooks = hooks)
    )
  )

  private val projectHookFields = Seq(
    ProjectHookField(
      "beforeVersionResolutionHooks",
      _.beforeVersionResolutionHooks.map(_.name),
      (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    ),
    ProjectHookField(
      "afterVersionResolutionHooks",
      _.afterVersionResolutionHooks.map(_.name),
      (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    ),
    ProjectHookField(
      "beforeReleaseVersionWriteHooks",
      _.beforeReleaseVersionWriteHooks.map(_.name),
      (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    ),
    ProjectHookField(
      "afterReleaseVersionWriteHooks",
      _.afterReleaseVersionWriteHooks.map(_.name),
      (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    ),
    ProjectHookField(
      "beforeTagHooks",
      _.beforeTagHooks.map(_.name),
      (config, hooks) => config.copy(beforeTagHooks = hooks)
    ),
    ProjectHookField(
      "afterTagHooks",
      _.afterTagHooks.map(_.name),
      (config, hooks) => config.copy(afterTagHooks = hooks)
    ),
    ProjectHookField(
      "beforePublishHooks",
      _.beforePublishHooks.map(_.name),
      (config, hooks) => config.copy(beforePublishHooks = hooks)
    ),
    ProjectHookField(
      "afterPublishHooks",
      _.afterPublishHooks.map(_.name),
      (config, hooks) => config.copy(afterPublishHooks = hooks)
    ),
    ProjectHookField(
      "beforeNextVersionWriteHooks",
      _.beforeNextVersionWriteHooks.map(_.name),
      (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    ),
    ProjectHookField(
      "afterNextVersionWriteHooks",
      _.afterNextVersionWriteHooks.map(_.name),
      (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    )
  )

  private def stateResource(
      prefix: String,
      settings: Seq[Setting[?]]
  ) =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking(
        TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir).settings(
              (MonorepoHookConfiguration.defaultSettings ++ settings)*
            )
          ),
          currentProjectId = Some("root")
        )
      )
    }
}

object MonorepoHookConfigurationSpec {

  final case class PolicyField(
      name: String,
      accessor: MonorepoHookConfiguration => Boolean,
      withValue: (MonorepoHookConfiguration, Boolean) => MonorepoHookConfiguration
  )

  final case class GlobalHookField(
      name: String,
      namesOf: MonorepoHookConfiguration => Seq[String],
      withHooks: (
          MonorepoHookConfiguration,
          Seq[MonorepoGlobalHookIO]
      ) => MonorepoHookConfiguration
  )

  final case class ProjectHookField(
      name: String,
      namesOf: MonorepoHookConfiguration => Seq[String],
      withHooks: (
          MonorepoHookConfiguration,
          Seq[MonorepoProjectHookIO]
      ) => MonorepoHookConfiguration
  )
}
