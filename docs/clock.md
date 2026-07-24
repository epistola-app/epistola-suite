<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# Clock

## Summary

Epistola application time is owned by mediator context.

`MediatorContext` binds the application `Mediator` and the current `Clock`.
`EpistolaClock` is the static facade over that context: code can call
`EpistolaClock.instant()`,
`offsetDateTime()`, `localDate()`, or `yearMonth()` without taking a Spring
dependency.

The default clock is `Clock.systemUTC()`. Tests and background entrypoints can
bind a different clock for deterministic execution.

## Mediator Context

`MediatorContext` lives in `app.epistola.suite.mediator` and contains scoped
execution state:

- `mediator`
- `clock`
- optionally `principal`

`MediatorContext.runWithMediator(mediator) { ... }` captures the active
`EpistolaClock` and binds a full mediator context. `SpringMediator` also
creates a mediator context scope when it is called directly outside an existing
scope, so command/query handlers have a clock boundary even without an HTTP
filter or scheduler wrapper.

`MediatorContext.current()` still returns the bound mediator. Use
`MediatorContext.currentClock()` only when context ownership matters explicitly;
most application code should use `EpistolaClock`.

## EpistolaClock

`EpistolaClock` resolves the current clock in this order:

1. a local `EpistolaClock.withClock(...)` override
2. the clock bound in `MediatorContext`
3. UTC system time

The local override exists for tests and small scoped overrides. Mediator context
is the normal application boundary.

## Spring Clock Bean

`EpistolaClockConfiguration` provides a delegating Spring `Clock` bean when no
other `Clock` bean is present.

This bean is a compatibility bridge for any remaining Spring-managed component
that already injects `Clock`. New code should not introduce Spring `Clock`
injection unless there is a concrete reason; prefer mediator-bound execution
plus `EpistolaClock`.

## Context Propagation

Code that starts outside an existing mediator scope and executes immediately
uses `MediatorContext.runWithMediator(mediator)`. Work that crosses an executor
or callback boundary uses `MediatorContext.runnable(...)` or
`MediatorContext.callable(...)`, which captures the current mediator context at
submission time and re-binds it inside the submitted work.

Use `MediatorContext.runWithMediator(mediator)` for scheduler-style entrypoints
that bind and execute immediately. Use
`MediatorContext.runnable(mediator, principal) { ... }` when work crosses a
thread boundary and needs a system/user principal.

Scoped values do not propagate to new threads automatically. The
`MediatorContext` handoff helpers capture and re-bind the context; "background"
is just one place where that propagation is needed.

## Test Infrastructure

Integration tests inherit `EpistolaClockExtension` through
`IntegrationTestBase`.

The extension creates a per-test `MutableClock`, binds it through
`EpistolaClock`, and exposes it as `testClock`.

```kotlin
testClock.reset()
testClock.advanceBy(Duration.ofSeconds(30))
```

This lets timer and scheduler tests advance time deterministically without
sleeping, Awaitility, or shared global mutable state.

## Design Rules

Use one of:

- `EpistolaClock.instant()` or another helper for application time
- `MediatorContext.currentClock()` when code specifically needs the execution
  context clock
- `MediatorContext.runWithMediator(mediator)` for scheduled work that executes immediately
- `MediatorContext.runnable(...)` / `MediatorContext.callable(...)` for executor handoffs
- the delegating Spring `Clock` only for transitional injected-clock code

Avoid direct application calls to:

- `Instant.now()`
- `OffsetDateTime.now()`
- `LocalDate.now()`
- `YearMonth.now()`

Keep database `NOW()`/`now()` for DB-owned timestamps and comparisons where
DB-clock alignment is intentional, such as triggers, row leases, claim/update
timestamps, and queries comparing database-owned timestamp columns.

## Current Transitional Areas

No production application component should inject Spring `Clock` for application
time. The remaining explicit `Clock` parameters are the mediator context facade
mechanics and the generation module's pure rendering API, where `epistola-core`
is intentionally not a dependency.

Infrastructure liveness and metrics code may intentionally use real wall-clock
time when the signal is elapsed process time rather than application time.

## Related Documentation

- [`docs/timers.md`](timers.md)
- [`docs/horizontal-scaling-phase1.md`](horizontal-scaling-phase1.md)
