# Clock

## Summary

Epistola has a shared application clock abstraction called `EpistolaClock`.
It is a small `ScopedValue`-backed facade over `java.time.Clock`.

The goal is to make application time explicit, deterministic in tests, and
safe to carry through mediator and background execution. We are not fully there
yet: the current implementation supports cluster timers and the test
infrastructure, while older code may still call `Instant.now()`,
`OffsetDateTime.now()`, or `LocalDate.now()` directly.

## Current Implementation

`EpistolaClock` lives in `app.epistola.suite.time`.

It exposes:

- `current()`: returns the scoped clock if one is bound, otherwise UTC system
  time
- `capture()`: captures the current clock for later execution
- `instant()`
- `offsetDateTime()`
- `localDate(zone)`
- `yearMonth(zone)`
- `withClock(clock) { ... }`
- `withInstant(instant) { ... }`

The default clock is `Clock.systemUTC()`.

`EpistolaClock` is intentionally static. Code can use it without requiring a
Spring dependency, and framework entry points can bind scoped time around a
block of work.

## Spring Clock Bean

`EpistolaClockConfiguration` provides a `Clock` bean when no other `Clock` bean
is present.

That bean delegates back to `EpistolaClock.current()`. This means code that
already injects `Clock` can participate in scoped time without changing every
call site immediately.

This is important for cluster timers and scheduled tasks because their
registries already use an injected `Clock`. Tests can bind a scoped mutable
clock and the registry code observes that time through the delegating Spring
bean.

## Scoped Values

`EpistolaClock.withClock(clock) { ... }` binds the clock for the current scoped
execution. Nested scopes are allowed and restore the previous clock when the
inner scope exits.

The scoped clock does not automatically flow to arbitrary new threads. When
work crosses thread boundaries, the current clock must be captured and rebound
in the new execution context.

## Background Execution

`BackgroundExecutionContext` is the current bridge for background work.

It captures the current `EpistolaClock`, then runs the block with:

- the captured clock rebound
- a `MediatorContext` bound to the application mediator
- optionally a `SecurityContext` principal for system/user scoped work

Cluster timer and scheduled-task pollers use this context before claiming and
dispatching work. That gives timer handlers a mediator context and consistent
clock behavior.

For executor handoffs, `BackgroundExecutionContext.wrap` and `wrapCallable`
capture the clock at wrapping time and rebind it when the runnable/callable is
executed.

## Test Infrastructure

Integration tests inherit `EpistolaClockExtension` through
`IntegrationTestBase`.

The extension creates a per-test `MutableClock`, binds it through
`EpistolaClock`, and exposes it as `testClock`.

Tests can use:

```kotlin
testClock.reset()
testClock.advanceBy(Duration.ofSeconds(30))
```

This lets timer and schedule tests advance time deterministically without
sleeping, polling with Awaitility, or sharing one mutable singleton clock across
parallel tests.

## Current Users

The current clock work was introduced to support clustered timers and scheduled
tasks.

Current direct users include:

- cluster timer registries and pollers through the Spring `Clock` bean
- scheduled-task registries and pollers through the Spring `Clock` bean
- `CommandCompleted` event timestamps through `EpistolaClock.instant()`
- focused unit and integration tests through `EpistolaClockExtension`

This is not yet a repo-wide migration. Many older code paths still use direct
JDK time calls.

## Design Rules For New Code

New application code should prefer one of:

- inject `Clock` when already inside Spring-managed code
- call `EpistolaClock.instant()` or another helper when a static access point is
  more appropriate
- use `BackgroundExecutionContext` for background work that needs mediator
  access or may cross thread boundaries

Avoid direct calls to:

- `Instant.now()`
- `OffsetDateTime.now()`
- `LocalDate.now()`
- `YearMonth.now()`

Direct JDK calls are still acceptable in infrastructure where real wall-clock
time is explicitly intended, but that should be the exception rather than the
default for application behavior.

## Why Not Only Inject Clock

Injected `Clock` works well inside Spring beans, but Epistola also has:

- mediator command/query/event objects
- static helper paths
- tests that need scoped time around a whole invocation
- background work that may not be created directly by Spring

`EpistolaClock` gives those paths one shared source of time without forcing
every object to carry a `Clock` dependency. The Spring `Clock` bean remains
useful because it lets existing injected-clock code participate in the same
scope.

## Future Direction

The target state is that mediator execution has an explicit execution context
that includes the application clock.

In that future:

- every command/query/event handler runs with an `EpistolaClock` scope
- background work always enters through `BackgroundExecutionContext` or an
  equivalent mediator-bound runner
- async handoffs capture and rebind both mediator context and clock context
- tests can advance time at the mediator boundary and all participating code
  observes the same deterministic time

This should be implemented deliberately. A blind sweep replacing every
`now()` call would be risky because some calls may intentionally use real
wall-clock time.

## Migration Plan

1. Keep `EpistolaClock` and the delegating Spring `Clock` as the stable API.
2. Require new background workers to use `BackgroundExecutionContext`.
3. Add mediator-level clock binding so command/query/event handlers can rely on
   a scoped clock by default.
4. Gradually migrate application-level direct `now()` calls in touched code.
5. Leave infrastructure wall-clock calls explicit and documented.
6. Add linting or review guidance once the intended boundaries are clear.

Good early migration candidates:

- command/event timestamps
- retry and backoff calculations
- scheduled work
- tenant-visible timestamps created by application workflows
- tests that currently depend on sleeps or wall-clock timing

Lower-priority or intentionally wall-clock areas:

- metrics emission timestamps
- low-level logging
- external library internals
- infrastructure health checks where real time is the signal

## Open Questions

- Should mediator context own the clock directly, or should it only guarantee
  that an `EpistolaClock` scope exists?
- Should command dispatch capture time once per top-level command, or should
  nested commands observe fresh current time?
- Do we need named test clocks for scenarios with multiple independent time
  domains, or is one scoped application clock enough?
- Which direct wall-clock usages are intentional infrastructure behavior?
- Should system background work always run with a system principal and mediator
  context, or are there legitimate non-mediated workers?

## Related Documentation

- [`docs/timers.md`](timers.md)
- [`docs/horizontal-scaling-phase1.md`](horizontal-scaling-phase1.md)
