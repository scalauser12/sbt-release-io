package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.monorepo.internal.*
import io.release.runtime.TrackedContextHandle
import munit.CatsEffectSuite
import sbt.AttributeKey

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

  test("MonorepoGlobalHookIO - preserve the legacy constructor, copy, and extractor shape") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("copied").flatMap { marker =>
        val hook   = MonorepoGlobalHookIO(
          name = "legacy-global-hook",
          execute = currentCtx => IO.pure(currentCtx.withProjects(Seq(marker))),
          validate = _ => IO.unit
        )
        val copied = hook.copy(name = "copied-global-hook")

        copied.execute(ctx).map { result =>
          val MonorepoGlobalHookIO(name, execute, validate) = hook
          assertEquals(name, "legacy-global-hook")
          assertEquals(validate, hook.validate)
          assertEquals(execute, hook.execute)
          assertEquals(copied.name, "copied-global-hook")
          assertEquals(result.projects, Seq(marker))
        }
      }
    }
  }

  test("MonorepoGlobalHookIO.ioTracked - checkpoint updates through the tracked handle") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val metadataKey = AttributeKey[String]("tracked-global-hook")
      val hook        = MonorepoGlobalHookIO.ioTracked("tracked-global") { handle =>
        handle.update(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, "updated"))).void
      }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        MonorepoGlobalHookIO.trackedExecute(hook)(handle) *> handle.get.map { result =>
          assertEquals(result.metadata(metadataKey), Some("updated"))
        }
      }
    }
  }

  test("MonorepoGlobalHookIO.ioTracked - copy preserves tracked execution") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val metadataKey = AttributeKey[String]("tracked-global-hook-copy")
      val hook        = MonorepoGlobalHookIO
        .ioTracked("tracked-global") { handle =>
          handle.update(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, "copied"))).void
        }
        .copy(name = "tracked-global-copy")

      TrackedContextHandle.create(ctx).flatMap { handle =>
        MonorepoGlobalHookIO.trackedExecute(hook)(handle) *> handle.get.map { result =>
          assertEquals(hook.name, "tracked-global-copy")
          assertEquals(result.metadata(metadataKey), Some("copied"))
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // MonorepoGlobalHookIO.sideEffect / .transform / .resumable
  // ---------------------------------------------------------------------------

  test("MonorepoGlobalHookIO.sideEffect - runs the effect and preserves the context") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, Int](0).flatMap { counter =>
        val hook = MonorepoGlobalHookIO.sideEffect("side-effect-global")(_ => counter.update(_ + 1))

        TrackedContextHandle.create(ctx).flatMap { handle =>
          for {
            _           <- MonorepoGlobalHookIO.trackedExecute(hook)(handle)
            latest      <- handle.get
            invocations <- counter.get
          } yield {
            assertEquals(latest, ctx)
            assertEquals(invocations, 1)
          }
        }
      }
    }
  }

  test("MonorepoGlobalHookIO.transform - checkpoints the returned context") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val metadataKey = AttributeKey[String]("transform-global")
      val hook        = MonorepoGlobalHookIO.transform("transform-global") { c =>
        IO.pure(c.withMetadata(metadataKey, "applied"))
      }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        MonorepoGlobalHookIO.trackedExecute(hook)(handle) *> handle.get.map { latest =>
          assertEquals(latest.metadata(metadataKey), Some("applied"))
        }
      }
    }
  }

  test("MonorepoGlobalHookIO.resumable - exposes the tracked handle directly") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val metadataKey = AttributeKey[Vector[String]]("resumable-global")
      val hook        = MonorepoGlobalHookIO.resumable("resumable-global") { handle =>
        def append(currentCtx: MonorepoContext, entry: String): MonorepoContext =
          currentCtx.withMetadata(
            metadataKey,
            currentCtx.metadata(metadataKey).getOrElse(Vector.empty) :+ entry
          )

        handle.update(c => IO.pure(append(c, "first"))).void *>
          handle.update(c => IO.pure(append(c, "second"))).void
      }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        MonorepoGlobalHookIO.trackedExecute(hook)(handle) *> handle.get.map { latest =>
          assertEquals(latest.metadata(metadataKey), Some(Vector("first", "second")))
        }
      }
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

  test("MonorepoProjectHookIO - preserve the legacy constructor, copy, and extractor shape") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val hook   = MonorepoProjectHookIO(
          name = "legacy-project-hook",
          execute = (currentCtx, currentProject) =>
            IO.pure(currentCtx.withProjects(Seq(currentProject.copy(tagName = Some("tagged"))))),
          validate = (_, _) => IO.unit
        )
        val copied = hook.copy(name = "copied-project-hook")

        copied.execute(ctx, project).map { result =>
          val MonorepoProjectHookIO(name, execute, validate) = hook
          assertEquals(name, "legacy-project-hook")
          assertEquals(validate, hook.validate)
          assertEquals(execute, hook.execute)
          assertEquals(copied.name, "copied-project-hook")
          assertEquals(result.projects.head.tagName, Some("tagged"))
        }
      }
    }
  }

  test("MonorepoProjectHookIO.ioTracked - checkpoint project-aware updates through the handle") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val metadataKey = AttributeKey[String]("tracked-project-hook")
        val hook        = MonorepoProjectHookIO.ioTracked("tracked-project") { (handle, p) =>
          handle.update(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, p.name))).void
        }

        TrackedContextHandle.create(ctx).flatMap { handle =>
          MonorepoProjectHookIO.trackedExecute(hook)(handle, project) *> handle.get.map { result =>
            assertEquals(result.metadata(metadataKey), Some("core"))
          }
        }
      }
    }
  }

  test("MonorepoProjectHookIO.ioTracked - copy preserves tracked execution") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val metadataKey = AttributeKey[String]("tracked-project-hook-copy")
        val hook        = MonorepoProjectHookIO
          .ioTracked("tracked-project") { (handle, currentProject) =>
            handle
              .update(currentCtx =>
                IO.pure(currentCtx.withMetadata(metadataKey, currentProject.name))
              )
              .void
          }
          .copy(name = "tracked-project-copy")

        TrackedContextHandle.create(ctx).flatMap { handle =>
          MonorepoProjectHookIO.trackedExecute(hook)(handle, project) *> handle.get.map { result =>
            assertEquals(hook.name, "tracked-project-copy")
            assertEquals(result.metadata(metadataKey), Some("core"))
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // MonorepoProjectHookIO.sideEffect / .transform / .resumable
  // ---------------------------------------------------------------------------

  test("MonorepoProjectHookIO.sideEffect - receives (project, ctx) and preserves the context") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        Ref.of[IO, Option[String]](None).flatMap { captured =>
          val hook = MonorepoProjectHookIO.sideEffect("side-effect-project") { (p, _) =>
            captured.set(Some(p.name))
          }

          TrackedContextHandle.create(ctx).flatMap { handle =>
            for {
              _        <- MonorepoProjectHookIO.trackedExecute(hook)(handle, project)
              latest   <- handle.get
              observed <- captured.get
            } yield {
              assertEquals(latest, ctx)
              assertEquals(observed, Some("core"))
            }
          }
        }
      }
    }
  }

  test("MonorepoProjectHookIO.transform - receives (project, ctx) and checkpoints the result") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val metadataKey = AttributeKey[String]("transform-project")
        val hook        = MonorepoProjectHookIO.transform("transform-project") { (p, c) =>
          IO.pure(c.withMetadata(metadataKey, p.name))
        }

        TrackedContextHandle.create(ctx).flatMap { handle =>
          MonorepoProjectHookIO.trackedExecute(hook)(handle, project) *> handle.get.map { latest =>
            assertEquals(latest.metadata(metadataKey), Some("core"))
          }
        }
      }
    }
  }

  test("MonorepoProjectHookIO.resumable - exposes (project, handle) directly") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val metadataKey = AttributeKey[Vector[String]]("resumable-project")
        val hook        = MonorepoProjectHookIO.resumable("resumable-project") { (p, handle) =>
          def append(currentCtx: MonorepoContext, entry: String): MonorepoContext =
            currentCtx.withMetadata(
              metadataKey,
              currentCtx.metadata(metadataKey).getOrElse(Vector.empty) :+ entry
            )

          handle.update(c => IO.pure(append(c, s"${p.name}-first"))).void *>
            handle.update(c => IO.pure(append(c, s"${p.name}-second"))).void
        }

        TrackedContextHandle.create(ctx).flatMap { handle =>
          MonorepoProjectHookIO.trackedExecute(hook)(handle, project) *> handle.get.map { latest =>
            assertEquals(latest.metadata(metadataKey), Some(Vector("core-first", "core-second")))
          }
        }
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

  test("MonorepoGlobalResourceHookIO.ioTracked - checkpoint resource-backed updates") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val metadataKey = AttributeKey[String]("tracked-global-resource-hook")
      val hook        = MonorepoGlobalResourceHookIO.ioTracked[String]("tracked-resource-global") {
        resource => handle =>
          handle.update(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, resource))).void
      }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        MonorepoGlobalResourceHookIO.trackedExecute(hook)("resource")(handle) *> handle.get.map {
          result =>
            assertEquals(result.metadata(metadataKey), Some("resource"))
        }
      }
    }
  }

  test(
    "MonorepoGlobalResourceHookIO - preserve the legacy constructor, copy, and extractor shape"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val metadataKey = AttributeKey[String]("legacy-global-resource")
      val hook        = MonorepoGlobalResourceHookIO[String](
        name = "legacy-global-resource-hook",
        execute = resource => currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, resource)),
        validate = _ => IO.unit
      )
      val copied      = hook.copy(name = "copied-global-resource-hook")

      copied.execute("bound")(ctx).map { result =>
        val MonorepoGlobalResourceHookIO(name, execute, validate) = hook
        assertEquals(name, "legacy-global-resource-hook")
        assertEquals(validate, hook.validate)
        assertEquals(execute, hook.execute)
        assertEquals(copied.name, "copied-global-resource-hook")
        assertEquals(result.metadata(metadataKey), Some("bound"))
      }
    }
  }

  // ---------------------------------------------------------------------------
  // MonorepoGlobalResourceHookIO.sideEffect / .transform / .resumable
  // ---------------------------------------------------------------------------

  test("MonorepoGlobalResourceHookIO.sideEffect - runs the effect and preserves the context") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, Option[String]](None).flatMap { captured =>
        val hook = MonorepoGlobalResourceHookIO.sideEffect[String]("side-effect-global-resource") {
          (resource, _) => captured.set(Some(resource))
        }

        TrackedContextHandle.create(ctx).flatMap { handle =>
          for {
            _        <- MonorepoGlobalResourceHookIO.trackedExecute(hook)("client")(handle)
            latest   <- handle.get
            observed <- captured.get
          } yield {
            assertEquals(latest, ctx)
            assertEquals(observed, Some("client"))
          }
        }
      }
    }
  }

  test("MonorepoGlobalResourceHookIO.transform - checkpoints the returned context") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val metadataKey = AttributeKey[String]("transform-global-resource")
      val hook        =
        MonorepoGlobalResourceHookIO.transform[String]("transform-global-resource") {
          (resource, c) => IO.pure(c.withMetadata(metadataKey, resource))
        }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        MonorepoGlobalResourceHookIO.trackedExecute(hook)("applied")(handle) *>
          handle.get.map { latest =>
            assertEquals(latest.metadata(metadataKey), Some("applied"))
          }
      }
    }
  }

  test("MonorepoGlobalResourceHookIO.resumable - exposes (resource, handle) directly") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val metadataKey = AttributeKey[Vector[String]]("resumable-global-resource")
      val hook        =
        MonorepoGlobalResourceHookIO.resumable[String]("resumable-global-resource") {
          (resource, handle) =>
            def append(currentCtx: MonorepoContext, entry: String): MonorepoContext =
              currentCtx.withMetadata(
                metadataKey,
                currentCtx.metadata(metadataKey).getOrElse(Vector.empty) :+ entry
              )

            handle.update(c => IO.pure(append(c, s"$resource-first"))).void *>
              handle.update(c => IO.pure(append(c, s"$resource-second"))).void
        }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        MonorepoGlobalResourceHookIO.trackedExecute(hook)("client")(handle) *>
          handle.get.map { latest =>
            assertEquals(
              latest.metadata(metadataKey),
              Some(Vector("client-first", "client-second"))
            )
          }
      }
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

  test(
    "MonorepoProjectResourceHookIO - preserve the legacy constructor, copy, and extractor shape"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val hook   = MonorepoProjectResourceHookIO[String](
          name = "legacy-project-resource-hook",
          execute = resource =>
            (currentCtx, currentProject) =>
              IO.pure(currentCtx.withProjects(Seq(currentProject.copy(tagName = Some(resource))))),
          validate = (_, _) => IO.unit
        )
        val copied = hook.copy(name = "copied-project-resource-hook")

        copied.execute("bound")(ctx, project).map { result =>
          val MonorepoProjectResourceHookIO(name, execute, validate) = hook
          assertEquals(name, "legacy-project-resource-hook")
          assertEquals(validate, hook.validate)
          assertEquals(execute, hook.execute)
          assertEquals(copied.name, "copied-project-resource-hook")
          assertEquals(result.projects.head.tagName, Some("bound"))
        }
      }
    }
  }

  test("MonorepoProjectResourceHookIO.ioTracked - copy preserves tracked execution") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val metadataKey = AttributeKey[String]("tracked-project-resource-hook-copy")
        val hook        = MonorepoProjectResourceHookIO
          .ioTracked[String]("tracked-project-resource") { resource => (handle, _) =>
            handle
              .update(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, resource)))
              .void
          }
          .copy(name = "tracked-project-resource-copy")

        TrackedContextHandle.create(ctx).flatMap { handle =>
          MonorepoProjectResourceHookIO
            .trackedExecute(hook)("copied-resource")(handle, project) *> handle.get.map { result =>
            assertEquals(hook.name, "tracked-project-resource-copy")
            assertEquals(result.metadata(metadataKey), Some("copied-resource"))
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // MonorepoProjectResourceHookIO.sideEffect / .transform / .resumable
  // ---------------------------------------------------------------------------

  test(
    "MonorepoProjectResourceHookIO.sideEffect - receives (resource, project, ctx) and preserves the context"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        Ref.of[IO, Option[(String, String)]](None).flatMap { captured =>
          val hook =
            MonorepoProjectResourceHookIO.sideEffect[String]("side-effect-project-resource") {
              (resource, p, _) => captured.set(Some((resource, p.name)))
            }

          TrackedContextHandle.create(ctx).flatMap { handle =>
            for {
              _        <- MonorepoProjectResourceHookIO
                            .trackedExecute(hook)("client")(handle, project)
              latest   <- handle.get
              observed <- captured.get
            } yield {
              assertEquals(latest, ctx)
              assertEquals(observed, Some(("client", "core")))
            }
          }
        }
      }
    }
  }

  test(
    "MonorepoProjectResourceHookIO.transform - receives (resource, project, ctx) and checkpoints the result"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val metadataKey = AttributeKey[String]("transform-project-resource")
        val hook        =
          MonorepoProjectResourceHookIO.transform[String]("transform-project-resource") {
            (resource, p, c) => IO.pure(c.withMetadata(metadataKey, s"$resource:${p.name}"))
          }

        TrackedContextHandle.create(ctx).flatMap { handle =>
          MonorepoProjectResourceHookIO.trackedExecute(hook)("client")(handle, project) *>
            handle.get.map { latest =>
              assertEquals(latest.metadata(metadataKey), Some("client:core"))
            }
        }
      }
    }
  }

  test("MonorepoProjectResourceHookIO.resumable - exposes (resource, project, handle) directly") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val metadataKey = AttributeKey[Vector[String]]("resumable-project-resource")
        val hook        =
          MonorepoProjectResourceHookIO.resumable[String]("resumable-project-resource") {
            (resource, p, handle) =>
              def append(currentCtx: MonorepoContext, entry: String): MonorepoContext =
                currentCtx.withMetadata(
                  metadataKey,
                  currentCtx.metadata(metadataKey).getOrElse(Vector.empty) :+ entry
                )

              handle.update(c => IO.pure(append(c, s"$resource-${p.name}-first"))).void *>
                handle.update(c => IO.pure(append(c, s"$resource-${p.name}-second"))).void
          }

        TrackedContextHandle.create(ctx).flatMap { handle =>
          MonorepoProjectResourceHookIO.trackedExecute(hook)("client")(handle, project) *>
            handle.get.map { latest =>
              assertEquals(
                latest.metadata(metadataKey),
                Some(Vector("client-core-first", "client-core-second"))
              )
            }
        }
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

  test(
    "MonorepoResourceHooks.materialize - populated global hooks only fill the intended slot"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { _ =>
      val hook   =
        MonorepoGlobalResourceHookIO.action[String]("before-selection")(_ => _ => IO.unit)
      val config = MonorepoResourceHooks.materialize(
        MonorepoResourceHooks[String](beforeSelectionHooks = Seq(hook)),
        None
      )

      IO {
        assertEquals(
          config.beforeSelectionHooks.map(_.name),
          Seq("before-selection")
        )
        // Other global hook fields should be empty
        assertEquals(config.afterCleanCheckHooks, Seq.empty)
        assertEquals(config.afterSelectionHooks, Seq.empty)
        assertEquals(config.beforeReleaseCommitHooks, Seq.empty)
        assertEquals(config.beforePushHooks, Seq.empty)
      }
    }
  }

  test(
    "MonorepoResourceHooks.materialize - populated project hooks only fill the intended slot"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { _ =>
      val hook   =
        MonorepoProjectResourceHookIO.action[String]("before-tag")(_ => (_, _) => IO.unit)
      val config = MonorepoResourceHooks.materialize(
        MonorepoResourceHooks[String](beforeTagHooks = Seq(hook)),
        None
      )

      IO {
        assertEquals(
          config.beforeTagHooks.map(_.name),
          Seq("before-tag")
        )
        // Other project hook fields should be empty
        assertEquals(config.afterTagHooks, Seq.empty)
        assertEquals(config.beforePublishHooks, Seq.empty)
        assertEquals(config.afterPublishHooks, Seq.empty)
      }
    }
  }

  test("MonorepoResourceHooks.materialize - preserves validate function from resource hook") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val resourceHook = MonorepoGlobalResourceHookIO[String](
          name = "hook-with-validate",
          execute = (_: String) => (currentCtx: MonorepoContext) => IO.pure(currentCtx),
          validate = (_: MonorepoContext) => log.update(_ :+ "validated")
        )
        val hooks        =
          MonorepoResourceHooks[String](beforeSelectionHooks = Seq(resourceHook))
        val config       = MonorepoResourceHooks.materialize(hooks, Some("res"))

        config.beforeSelectionHooks.head.validate(ctx).flatMap { _ =>
          log.get.map(events => assertEquals(events, List("validated")))
        }
      }
    }
  }

  test(
    "MonorepoResourceHooks.materialize - preserve tracked global execute when binding resource"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      val metadataKey = AttributeKey[String]("materialized-tracked-global-resource-hook")
      val hook        = MonorepoGlobalResourceHookIO.ioTracked[String]("tracked-global") {
        resource => handle =>
          handle.update(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, resource))).void
      }
      val config      = MonorepoResourceHooks.materialize(
        MonorepoResourceHooks[String](beforeSelectionHooks = Seq(hook)),
        Some("bound-global")
      )

      TrackedContextHandle.create(ctx).flatMap { handle =>
        MonorepoGlobalHookIO.trackedExecute(config.beforeSelectionHooks.head)(handle) *>
          handle.get.map { result =>
            assertEquals(result.metadata(metadataKey), Some("bound-global"))
          }
      }
    }
  }

  test(
    "MonorepoResourceHooks.materialize - preserve tracked project execute when binding resource"
  ) {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { ctx =>
      dummyProject("core").flatMap { project =>
        val metadataKey = AttributeKey[String]("materialized-tracked-project-resource-hook")
        val hook        = MonorepoProjectResourceHookIO.ioTracked[String]("tracked-project") {
          resource => (handle, currentProject) =>
            handle
              .update(currentCtx =>
                IO.pure(currentCtx.withMetadata(metadataKey, s"$resource:${currentProject.name}"))
              )
              .void
        }
        val config      = MonorepoResourceHooks.materialize(
          MonorepoResourceHooks[String](beforeTagHooks = Seq(hook)),
          Some("bound-project")
        )

        TrackedContextHandle.create(ctx).flatMap { handle =>
          MonorepoProjectHookIO.trackedExecute(config.beforeTagHooks.head)(handle, project) *>
            handle.get.map { result =>
              assertEquals(result.metadata(metadataKey), Some("bound-project:core"))
            }
        }
      }
    }
  }

  test("MonorepoResourceHooks.materialize - empty hooks produce empty config") {
    MonorepoSpecSupport.dummyContextResource("monorepo-hook-io-spec").use { _ =>
      IO {
        val config =
          MonorepoResourceHooks.materialize(
            MonorepoResourceHooks.empty[String],
            None
          )
        assertEquals(config, MonorepoHookConfiguration.empty)
      }
    }
  }
}
