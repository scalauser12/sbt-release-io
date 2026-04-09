package io.release.monorepo

import io.release.monorepo.internal.*

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite

class MonorepoHookIOSpec extends CatsEffectSuite with MonorepoDummyProjectSupport {

  // ---------------------------------------------------------------------------
  // MonorepoGlobalHookIO
  // ---------------------------------------------------------------------------

  test("MonorepoGlobalHookIO.io - stores the provided name on the hook") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val hook = MonorepoGlobalHookIO.io("my-global-hook")(IO.pure)
      IO(assertEquals(hook.name, "my-global-hook"))
    }
  }

  test("MonorepoGlobalHookIO.io - execute delegates to the provided function") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("marker").flatMap { marker =>
        val modifyCtx = (c: MonorepoContext) => IO.pure(c.withProjects(Seq(marker)))
        val hook      = MonorepoGlobalHookIO.io("transform-hook")(modifyCtx)

        hook.execute(ctx).map { result =>
          assertEquals(result.projects, Seq(marker))
        }
      }
    }
  }

  test("MonorepoGlobalHookIO.action - stores the provided name on the hook") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val hook = MonorepoGlobalHookIO.action("my-action-hook")(_ => IO.unit)
      IO(assertEquals(hook.name, "my-action-hook"))
    }
  }

  test("MonorepoGlobalHookIO.action - execute runs the effect and returns context unchanged") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val hook = MonorepoGlobalHookIO.action("log-hook")(_ => log.update(_ :+ "ran"))

        hook.execute(ctx).flatMap { result =>
          log.get.map { events =>
            assertEquals(events, List("ran"))
            assertEquals(result, ctx)
          }
        }
      }
    }
  }

  test("MonorepoGlobalHookIO.io - default validate is a no-op") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val hook = MonorepoGlobalHookIO.io("validate-noop")(IO.pure)
      hook.validate(ctx).map(_ => assert(true))
    }
  }

  test("MonorepoGlobalHookIO.action - default validate is a no-op") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val hook = MonorepoGlobalHookIO.action("validate-noop-action")(_ => IO.unit)
      hook.validate(ctx).map(_ => assert(true))
    }
  }

  // ---------------------------------------------------------------------------
  // MonorepoProjectHookIO
  // ---------------------------------------------------------------------------

  test("MonorepoProjectHookIO.io - stores the provided name on the hook") {
    val hook = MonorepoProjectHookIO.io("my-project-hook")((ctx, _) => IO.pure(ctx))
    assertEquals(hook.name, "my-project-hook")
  }

  test("MonorepoProjectHookIO.io - execute delegates to the provided function") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val tagged    = project.copy(tagName = Some("core-v1.0.0"))
        val modifyCtx =
          (c: MonorepoContext, _: ProjectReleaseInfo) => IO.pure(c.withProjects(Seq(tagged)))
        val hook      = MonorepoProjectHookIO.io("tag-hook")(modifyCtx)

        hook.execute(ctx, project).map { result =>
          assertEquals(result.projects.head.tagName, Some("core-v1.0.0"))
        }
      }
    }
  }

  test("MonorepoProjectHookIO.action - stores the provided name on the hook") {
    val hook = MonorepoProjectHookIO.action("my-project-action")((_, _) => IO.unit)
    assertEquals(hook.name, "my-project-action")
  }

  test("MonorepoProjectHookIO.action - execute runs the effect and returns context unchanged") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        dummyProject("api").flatMap { project =>
          val hook =
            MonorepoProjectHookIO.action("log-project-hook")((_, p) => log.update(_ :+ p.name))

          hook.execute(ctx, project).flatMap { result =>
            log.get.map { events =>
              assertEquals(events, List("api"))
              assertEquals(result, ctx)
            }
          }
        }
      }
    }
  }

  test("MonorepoProjectHookIO.io - default validate is a no-op") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val hook = MonorepoProjectHookIO.io("validate-noop-project")((c, _) => IO.pure(c))
        hook.validate(ctx, project).map(_ => assert(true))
      }
    }
  }

  test("MonorepoProjectHookIO.action - default validate is a no-op") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val hook =
          MonorepoProjectHookIO.action("validate-noop-project-action")((_, _) => IO.unit)
        hook.validate(ctx, project).map(_ => assert(true))
      }
    }
  }

  // ---------------------------------------------------------------------------
  // MonorepoGlobalResourceHookIO
  // ---------------------------------------------------------------------------

  test("MonorepoGlobalResourceHookIO.io - stores the provided name on the hook") {
    val hook = MonorepoGlobalResourceHookIO.io[String]("resource-global-io")(_ => IO.pure)
    assertEquals(hook.name, "resource-global-io")
  }

  test("MonorepoGlobalResourceHookIO.io - execute delegates to the resource function") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("marker").flatMap { marker =>
        val hook = MonorepoGlobalResourceHookIO.io[String]("resource-io-hook") { resource => c =>
          IO.pure(c.withProjects(Seq(marker.copy(name = resource))))
        }

        hook.execute("injected")(ctx).map { result =>
          assertEquals(result.projects.head.name, "injected")
        }
      }
    }
  }

  test("MonorepoGlobalResourceHookIO.action - stores the provided name on the hook") {
    val hook =
      MonorepoGlobalResourceHookIO.action[String]("resource-global-action")(_ => _ => IO.unit)
    assertEquals(hook.name, "resource-global-action")
  }

  test("MonorepoGlobalResourceHookIO.action - execute runs effect and returns context unchanged") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val hook = MonorepoGlobalResourceHookIO.action[String]("resource-action") { resource => _ =>
          log.update(_ :+ resource)
        }

        hook.execute("my-resource")(ctx).flatMap { result =>
          log.get.map { events =>
            assertEquals(events, List("my-resource"))
            assertEquals(result, ctx)
          }
        }
      }
    }
  }

  test("MonorepoGlobalResourceHookIO.io - default validate is a no-op") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val hook = MonorepoGlobalResourceHookIO.io[String]("resource-validate-noop")(_ => IO.pure)
      hook.validate(ctx).map(_ => assert(true))
    }
  }

  // ---------------------------------------------------------------------------
  // MonorepoProjectResourceHookIO
  // ---------------------------------------------------------------------------

  test("MonorepoProjectResourceHookIO.io - stores the provided name on the hook") {
    val hook =
      MonorepoProjectResourceHookIO.io[String]("resource-project-io")(_ => (ctx, _) => IO.pure(ctx))
    assertEquals(hook.name, "resource-project-io")
  }

  test("MonorepoProjectResourceHookIO.io - execute delegates to the resource function") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val hook = MonorepoProjectResourceHookIO.io[String]("project-resource-io") {
          resource => (c, p) =>
            IO.pure(c.withProjects(Seq(p.copy(tagName = Some(s"$resource-${p.name}")))))
        }

        hook.execute("prefix")(ctx, project).map { result =>
          assertEquals(result.projects.head.tagName, Some("prefix-core"))
        }
      }
    }
  }

  test("MonorepoProjectResourceHookIO.action - stores the provided name on the hook") {
    val hook =
      MonorepoProjectResourceHookIO.action[String]("resource-project-action")(_ =>
        (_, _) => IO.unit
      )
    assertEquals(hook.name, "resource-project-action")
  }

  test("MonorepoProjectResourceHookIO.action - execute runs effect and returns context unchanged") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        dummyProject("api").flatMap { project =>
          val hook =
            MonorepoProjectResourceHookIO.action[String]("project-resource-action") {
              resource => (_, p) =>
                log.update(_ :+ s"$resource:${p.name}")
            }

          hook.execute("res")(ctx, project).flatMap { result =>
            log.get.map { events =>
              assertEquals(events, List("res:api"))
              assertEquals(result, ctx)
            }
          }
        }
      }
    }
  }

  test("MonorepoProjectResourceHookIO.io - default validate is a no-op") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val hook =
          MonorepoProjectResourceHookIO.io[String]("resource-project-validate-noop") {
            _ => (c, _) => IO.pure(c)
          }
        hook.validate(ctx, project).map(_ => assert(true))
      }
    }
  }

  // ---------------------------------------------------------------------------
  // MonorepoResourceHooks.materialize
  // ---------------------------------------------------------------------------

  test(
    "MonorepoResourceHooks.materialize - with Some(resource) global hook calls through to resource function"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val resourceHook = MonorepoGlobalResourceHookIO.action[String]("before-selection") {
          resource => _ =>
            log.update(_ :+ resource)
        }
        val hooks        = MonorepoResourceHooks[String](beforeSelectionHooks = Seq(resourceHook))
        val config       = MonorepoResourceHooks.materialize(hooks, Some("my-resource"))

        config.beforeSelectionHooks.head.execute(ctx).flatMap { result =>
          log.get.map { events =>
            assertEquals(events, List("my-resource"))
            assertEquals(result, ctx)
          }
        }
      }
    }
  }

  test(
    "MonorepoResourceHooks.materialize - materializes after-clean-check global hooks"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val resourceHook = MonorepoGlobalResourceHookIO.action[String]("after-clean-check") {
          resource => _ =>
            log.update(_ :+ resource)
        }
        val hooks        = MonorepoResourceHooks[String](afterCleanCheckHooks = Seq(resourceHook))
        val config       = MonorepoResourceHooks.materialize(hooks, Some("my-resource"))

        config.afterCleanCheckHooks.head.execute(ctx).flatMap { result =>
          log.get.map { events =>
            assertEquals(events, List("my-resource"))
            assertEquals(result, ctx)
          }
        }
      }
    }
  }

  test(
    "MonorepoResourceHooks.materialize - with None global hook returns context unchanged without calling resource function"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val resourceHook = MonorepoGlobalResourceHookIO.action[String]("before-selection") {
          _ => _ =>
            log.update(_ :+ "should-not-run")
        }
        val hooks        = MonorepoResourceHooks[String](beforeSelectionHooks = Seq(resourceHook))
        val config       = MonorepoResourceHooks.materialize(hooks, None)

        config.beforeSelectionHooks.head.execute(ctx).flatMap { result =>
          log.get.map { events =>
            assertEquals(events, Nil)
            assertEquals(result, ctx)
          }
        }
      }
    }
  }

  test(
    "MonorepoResourceHooks.materialize - with Some(resource) per-project hook calls through to resource function"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        dummyProject("core").flatMap { project =>
          val resourceHook =
            MonorepoProjectResourceHookIO.action[String]("before-tag") { resource => (_, p) =>
              log.update(_ :+ s"$resource:${p.name}")
            }
          val hooks        = MonorepoResourceHooks[String](beforeTagHooks = Seq(resourceHook))
          val config       = MonorepoResourceHooks.materialize(hooks, Some("prefix"))

          config.beforeTagHooks.head.execute(ctx, project).flatMap { result =>
            log.get.map { events =>
              assertEquals(events, List("prefix:core"))
              assertEquals(result, ctx)
            }
          }
        }
      }
    }
  }

  test(
    "MonorepoResourceHooks.materialize - with None per-project hook returns context unchanged without calling resource function"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        dummyProject("core").flatMap { project =>
          val resourceHook =
            MonorepoProjectResourceHookIO.action[String]("before-tag") { _ => (_, _) =>
              log.update(_ :+ "should-not-run")
            }
          val hooks        = MonorepoResourceHooks[String](beforeTagHooks = Seq(resourceHook))
          val config       = MonorepoResourceHooks.materialize(hooks, None)

          config.beforeTagHooks.head.execute(ctx, project).flatMap { result =>
            log.get.map { events =>
              assertEquals(events, Nil)
              assertEquals(result, ctx)
            }
          }
        }
      }
    }
  }

  test("MonorepoResourceHooks.materialize - preserves hook names during materialization") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val globalHook  = MonorepoGlobalResourceHookIO.io[String]("named-global-hook")(_ => IO.pure)
      val projectHook =
        MonorepoProjectResourceHookIO.io[String]("named-project-hook")(_ => (c, _) => IO.pure(c))
      val hooks       =
        MonorepoResourceHooks[String](
          beforeSelectionHooks = Seq(globalHook),
          beforeTagHooks = Seq(projectHook)
        )
      val config      = MonorepoResourceHooks.materialize(hooks, None)

      IO {
        assertEquals(config.beforeSelectionHooks.head.name, "named-global-hook")
        assertEquals(config.beforeTagHooks.head.name, "named-project-hook")
      }
    }
  }

  test("MonorepoResourceHooks.materialize - all boolean policies default to true") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { _ =>
      val config = MonorepoResourceHooks.materialize(MonorepoResourceHooks.empty[String], None)

      IO {
        assert(config.enableSnapshotDependenciesCheck)
        assert(config.enableRunClean)
        assert(config.enableRunTests)
        assert(config.enableTagging)
        assert(config.enablePublish)
        assert(config.enablePush)
      }
    }
  }

  test("MonorepoResourceHooks.materialize - empty hooks produce the neutral configuration") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { _ =>
      IO(
        assertEquals(
          MonorepoResourceHooks.materialize(MonorepoResourceHooks.empty[String], None),
          MonorepoHookConfiguration.empty
        )
      )
    }
  }

  test("MonorepoResourceHooks.materialize - populated global hooks only fill the intended slot") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { _ =>
      val hook   = MonorepoGlobalResourceHookIO.action[String]("before-selection")(_ => _ => IO.unit)
      val config = MonorepoResourceHooks.materialize(
        MonorepoResourceHooks[String](beforeSelectionHooks = Seq(hook)),
        None
      )

      IO {
        val populatedSlots = MonorepoLifecycleSlots.globalHookSlots
          .filter(slot => slot.resolveHooks(config).nonEmpty)
          .map(_.keyLabel)
        assertEquals(populatedSlots, Seq(MonorepoGlobalHookSlots.beforeSelectionHooks.keyLabel))
        assertEquals(config.beforeSelectionHooks.map(_.name), Seq("before-selection"))
      }
    }
  }

  test("MonorepoResourceHooks.materialize - populated project hooks only fill the intended slot") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { _ =>
      val hook   = MonorepoProjectResourceHookIO.action[String]("before-tag")(_ => (_, _) => IO.unit)
      val config = MonorepoResourceHooks.materialize(
        MonorepoResourceHooks[String](beforeTagHooks = Seq(hook)),
        None
      )

      IO {
        val populatedSlots = MonorepoLifecycleSlots.projectHookSlots
          .filter(slot => slot.resolveHooks(config).nonEmpty)
          .map(_.keyLabel)
        assertEquals(populatedSlots, Seq(MonorepoProjectHookSlots.beforeTagHooks.keyLabel))
        assertEquals(config.beforeTagHooks.map(_.name), Seq("before-tag"))
      }
    }
  }

  test("MonorepoResourceHooks.materialize - preserves validate function from resource hook") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val resourceHook = MonorepoGlobalResourceHookIO[String](
          name = "hook-with-validate",
          execute = _ => IO.pure,
          validate = _ => log.update(_ :+ "validated")
        )
        val hooks        = MonorepoResourceHooks[String](beforeSelectionHooks = Seq(resourceHook))
        val config       = MonorepoResourceHooks.materialize(hooks, Some("res"))

        config.beforeSelectionHooks.head.validate(ctx).flatMap { _ =>
          log.get.map(events => assertEquals(events, List("validated")))
        }
      }
    }
  }

  test("MonorepoResourceHooks.globalHookAssignments covers every global hook slot exactly once") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { _ =>
      IO {
        val assignments = MonorepoResourceHooks.globalHookAssignments(
          MonorepoResourceHooks.empty[String],
          (resourceHook: MonorepoGlobalResourceHookIO[String]) =>
            MonorepoGlobalHookIO.action(resourceHook.name)(_ => IO.unit)
        )
        assertEquals(
          assignments.map(_._1.keyLabel),
          MonorepoLifecycleSlots.globalHookSlots.map(_.keyLabel)
        )
      }
    }
  }

  test("MonorepoResourceHooks.projectHookAssignments covers every project hook slot exactly once") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { _ =>
      IO {
        val assignments = MonorepoResourceHooks.projectHookAssignments(
          MonorepoResourceHooks.empty[String],
          (resourceHook: MonorepoProjectResourceHookIO[String]) =>
            MonorepoProjectHookIO.action(resourceHook.name)((_, _) => IO.unit)
        )
        assertEquals(
          assignments.map(_._1.keyLabel),
          MonorepoLifecycleSlots.projectHookSlots.map(_.keyLabel)
        )
      }
    }
  }
}
