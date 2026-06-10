# Clock

## Summary

Epistola application time is owned by the mediator execution context.

`MediatorContext` binds a `MediatorExecutionContext`, which carries the
application `Mediator` and the current `Clock`. `EpistolaClock` is the static
facade over that context: code can call `EpistolaClock.instant()`,
`offsetDateTime()`, `localDate()`, or `yearMonth()` without taking a Spring
dependency.

The default clock is `Clock.systemUTC()`. Tests and background entrypoints can
bind a different clock for deterministic execution.

## Mediator Execution Context

`MediatorExecutionContext` lives in `app.epistola.suite.mediator` and contains:

- `mediator`
- `clock`

`MediatorContext.runWithMediator(mediator) { ... }` captures the active
`EpistolaClock` and binds a full execution context. `SpringMediator` also
creates an execution context when it is called directly outside an existing
context, so command/query handlers have a clock boundary even without an HTTP
filter or scheduler wrapper.

`MediatorContext.current()` still returns the bound mediator. Use
`MediatorContext.currentClock()` only when context ownership matters explicitly;
most application code should use `EpistolaClock`.

## EpistolaClock

`EpistolaClock` resolves the current clock in this order:

1. a local `EpistolaClock.withClock(...)` override
2. the clock bound in `MediatorContext`
3. UTC system time

The local override exists for tests and small scoped overrides. The mediator
execution context is the normal application boundary.

## Spring Clock Bean

`EpistolaClockConfiguration` provides a delegating Spring `Clock` bean when no
other `Clock` bean is present.

This bean is a compatibility bridge for any remaining Spring-managed component
that already injects `Clock`. New code should not introduce Spring `Clock`
injection unless there is a concrete reason; prefer mediator-bound execution
plus `EpistolaClock`.

## Background Execution

`BackgroundExecutionContext` is the standard boundary for scheduled work and
executor handoffs. It captures the active clock and binds:

- `MediatorContext` with a full `MediatorExecutionContext`
- optionally `SecurityContext` for system/user scoped work

Use `run`, `runAs`, `wrap`, `wrapCallable`, or `wrapAs` when work starts outside
an HTTP request or crosses a thread boundary. Scoped values do not propagate to
new threads automatically.

Cluster timer and scheduled-task pollers, support schedulers, and executor
handoffs use this boundary so background work sees the same mediator and clock
model as request work.

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
- `BackgroundExecutionContext` for scheduled work and executor handoffs
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
time. The remaining explicit `Clock` parameters are the mediator execution
context/facade mechanics and the generation module's pure rendering API, where
`epistola-core` is intentionally not a dependency.

Infrastructure liveness and metrics code may intentionally use real wall-clock
time when the signal is elapsed process time rather than application time.

## Related Documentation

- [`docs/timers.md`](timers.md)
- [`docs/horizontal-scaling-phase1.md`](horizontal-scaling-phase1.md)
