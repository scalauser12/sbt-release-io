package io.release.core.internal

import cats.effect.IO
import io.release.ReleaseHookIO
import io.release.ReleasePluginIO
import io.release.TestSupport
import io.release.core.internal.CoreHookConfigurationSpec.HookField
import io.release.core.internal.CoreHookConfigurationSpec.PolicyField
import munit.CatsEffectSuite
import sbt.*

class CoreHookConfigurationSpec extends CatsEffectSuite {

  test("defaultSettings - expose each lifecycle-derived setting key exactly once") {
    IO {
      val labels =
        CoreHookConfiguration.defaultSettings.map(_.key.key.label)

      assertEquals(labels, labels.distinct)
      // 6 policy + 17 hook = 23 settings
      assertEquals(labels.size, 23)
    }
  }

  test("field coverage - update tables when CoreHookConfiguration fields change") {
    IO {
      assertEquals(
        CoreHookConfiguration.empty.productArity,
        policyFields.size + hookFields.size
      )
    }
  }

  test("resolve - read lifecycle policies and hook buckets from state") {
    stateResource(
      "core-hook-configuration-resolve",
      Seq(
        ReleasePluginIO.autoImport.releaseIOPolicyEnablePublish := false,
        ReleasePluginIO.autoImport.releaseIOHooksBeforeTag      := Seq(
          ReleaseHookIO.action("before-tag")(_ => IO.unit)
        ),
        ReleasePluginIO.autoImport.releaseIOHooksAfterPush      := Seq(
          ReleaseHookIO.action("after-push")(_ => IO.unit)
        )
      )
    ).use { state =>
      IO {
        val config = CoreHookConfiguration.resolve(state)

        assert(!config.enablePublish)
        assertEquals(config.beforeTagHooks.map(_.name), Seq("before-tag"))
        assertEquals(config.afterPushHooks.map(_.name), Seq("after-push"))
      }
    }
  }

  test("merge - combine every policy with logical and and append every hook bucket in order") {
    IO {
      val left   = hookFields.foldLeft(CoreHookConfiguration.empty) { (config, field) =>
        field.withHooks(
          config,
          Seq(ReleaseHookIO.action(s"${field.name}-left")(_ => IO.unit))
        )
      }
      val right  = hookFields
        .foldLeft(policyFields.foldLeft(CoreHookConfiguration.empty) { (config, field) =>
          field.withValue(config, false)
        }) { (config, field) =>
          field.withHooks(
            config,
            Seq(ReleaseHookIO.action(s"${field.name}-right")(_ => IO.unit))
          )
        }
      val merged = CoreHookConfiguration.merge(left, right)

      policyFields.foreach { field =>
        if (field.accessor(merged))
          fail(s"Expected merged policy '${field.name}' to be false")
      }
      hookFields.foreach { field =>
        val expected = Seq(s"${field.name}-left", s"${field.name}-right")
        val actual   = field.namesOf(merged)
        if (actual != expected)
          fail(
            s"Expected merged hook bucket '${field.name}' to preserve order: " +
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

  private val hookFields = Seq(
    HookField(
      "afterCleanCheckHooks",
      _.afterCleanCheckHooks.map(_.name),
      (config, hooks) => config.copy(afterCleanCheckHooks = hooks)
    ),
    HookField(
      "beforeVersionResolutionHooks",
      _.beforeVersionResolutionHooks.map(_.name),
      (config, hooks) => config.copy(beforeVersionResolutionHooks = hooks)
    ),
    HookField(
      "afterVersionResolutionHooks",
      _.afterVersionResolutionHooks.map(_.name),
      (config, hooks) => config.copy(afterVersionResolutionHooks = hooks)
    ),
    HookField(
      "beforeReleaseVersionWriteHooks",
      _.beforeReleaseVersionWriteHooks.map(_.name),
      (config, hooks) => config.copy(beforeReleaseVersionWriteHooks = hooks)
    ),
    HookField(
      "afterReleaseVersionWriteHooks",
      _.afterReleaseVersionWriteHooks.map(_.name),
      (config, hooks) => config.copy(afterReleaseVersionWriteHooks = hooks)
    ),
    HookField(
      "beforeReleaseCommitHooks",
      _.beforeReleaseCommitHooks.map(_.name),
      (config, hooks) => config.copy(beforeReleaseCommitHooks = hooks)
    ),
    HookField(
      "afterReleaseCommitHooks",
      _.afterReleaseCommitHooks.map(_.name),
      (config, hooks) => config.copy(afterReleaseCommitHooks = hooks)
    ),
    HookField(
      "beforeTagHooks",
      _.beforeTagHooks.map(_.name),
      (config, hooks) => config.copy(beforeTagHooks = hooks)
    ),
    HookField(
      "afterTagHooks",
      _.afterTagHooks.map(_.name),
      (config, hooks) => config.copy(afterTagHooks = hooks)
    ),
    HookField(
      "beforePublishHooks",
      _.beforePublishHooks.map(_.name),
      (config, hooks) => config.copy(beforePublishHooks = hooks)
    ),
    HookField(
      "afterPublishHooks",
      _.afterPublishHooks.map(_.name),
      (config, hooks) => config.copy(afterPublishHooks = hooks)
    ),
    HookField(
      "beforeNextVersionWriteHooks",
      _.beforeNextVersionWriteHooks.map(_.name),
      (config, hooks) => config.copy(beforeNextVersionWriteHooks = hooks)
    ),
    HookField(
      "afterNextVersionWriteHooks",
      _.afterNextVersionWriteHooks.map(_.name),
      (config, hooks) => config.copy(afterNextVersionWriteHooks = hooks)
    ),
    HookField(
      "beforeNextCommitHooks",
      _.beforeNextCommitHooks.map(_.name),
      (config, hooks) => config.copy(beforeNextCommitHooks = hooks)
    ),
    HookField(
      "afterNextCommitHooks",
      _.afterNextCommitHooks.map(_.name),
      (config, hooks) => config.copy(afterNextCommitHooks = hooks)
    ),
    HookField(
      "beforePushHooks",
      _.beforePushHooks.map(_.name),
      (config, hooks) => config.copy(beforePushHooks = hooks)
    ),
    HookField(
      "afterPushHooks",
      _.afterPushHooks.map(_.name),
      (config, hooks) => config.copy(afterPushHooks = hooks)
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
              (CoreHookConfiguration.defaultSettings ++ settings)*
            )
          ),
          currentProjectId = Some("root")
        )
      )
    }
}

object CoreHookConfigurationSpec {

  final case class PolicyField(
      name: String,
      accessor: CoreHookConfiguration => Boolean,
      withValue: (CoreHookConfiguration, Boolean) => CoreHookConfiguration
  )

  final case class HookField(
      name: String,
      namesOf: CoreHookConfiguration => Seq[String],
      withHooks: (CoreHookConfiguration, Seq[ReleaseHookIO]) => CoreHookConfiguration
  )
}
