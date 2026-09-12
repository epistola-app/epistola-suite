# epistola-core

All business logic: domains, their commands and queries, the mediator, JDBI configuration, and the
catalog and exchange packages (which have [their own guide](src/main/kotlin/app/epistola/suite/catalog/AGENTS.md)).
The REST controllers are **not** here — they live in `modules/rest-api`.

## Commands and queries

One file holds the message and its handler: `data class X(...) : Command<R>, RequiresPermission`,
then `@Component class XHandler(private val jdbi: Jdbi) : CommandHandler<X, R>`. Nothing is
registered by hand; `SpringMediator` resolves handlers by generic type. Copy
`environments/commands/CreateEnvironment.kt` (or `environments/queries/ListEnvironments.kt`).

- **Authorization is mandatory** — implement one marker from `security/Authorized.kt`:
  `RequiresPermission` (a `Permission` plus the `tenantKey` it applies to), `RequiresPlatformRole`,
  `RequiresAuthentication`, or `SystemInternal`. Say in a comment why a `SystemInternal` message
  bypasses checks.
- **Validation goes in `init {}`** as `validate("field", condition, code) { "message" }`. The `code`
  is a `ValidationCode`; give a real one for a new condition, because the REST layer maps it into
  the problem body. Use `FieldLimits` constants. `require(...)` throws the wrong type — it is not
  validation.
- **Data access lives in the handler**, with raw JDBI. Commands already run in one transaction, so
  do not add `@Transactional`; implement `SelfManagedTransaction` when you truly must manage it.
- **Audit columns**: bind `created_by` / `updated_by` from `currentUserIdOrNull()?.value`.
- Domains keep their handlers under `commands/` and `queries/`; a handler must not import another
  domain's handler.

## Application time

Time belongs to `MediatorContext`, not to Spring injection: it carries the `Mediator`, the current
`Clock` and optionally the `EpistolaPrincipal`, bound with `ScopedValue`, and `EpistolaClock` reads
from it.

- Use `EpistolaClock.instant()`, `offsetDateTime()`, `localDate()`, `yearMonth()`. Never a bare
  `Instant.now()` and never an injected Spring `Clock`.
- Handlers get their scope from `SpringMediator` and should not open their own. An entry point that
  starts outside one binds it explicitly with `MediatorContext.runWithMediator(mediator) { … }`.
- Work handed to another thread must capture the context first —
  `executor.submit(MediatorContext.runnable(mediator, principal) { … })`. Virtual threads are still
  separate threads; `ScopedValue` bindings do not cross executor boundaries on their own.
- Database `NOW()` stays right for database-owned timestamps, triggers, leases and claim
  comparisons. `modules/generation` may take an explicit `Clock` because it does not depend on core;
  callers pass `EpistolaClock.current()`.

Full picture: [`docs/clock.md`](../../docs/clock.md).

## Feature toggles

Reads go through CQRS queries: `GetFeatureToggles` (permission-gated, backs the admin page) and
`ResolveFeatureToggles` (`SystemInternal`, for UI rendering and schedulers). Both delegate to
`FeatureToggleService.resolveAll`, which memoizes per request through a `ScopedValue` cache bound by
`FeatureToggleCacheFilter`, so one page render issues one toggle query per tenant. Add a new read as
a query; only the resolution service touches JDBI.

## What core must not do

Core never calls a feature module. `epistola-quality` depends on core and subscribes; the generation
pipeline emits. That direction is a compile-time fact, not a convention — keep it that way.

## Verify

```bash
./gradlew :modules:epistola-core:integrationTest --tests "*YourTest*"
./gradlew :apps:epistola:unitTest --tests "app.epistola.suite.architecture.*"
```

`MediatorWiringTest` (one handler per message, `@Component`, `Authorized`), `AuthorizationCoverageTest`,
`DomainBoundaryTest` and `ApplicationClockUsageTest` are what fail when the rules above are broken.
