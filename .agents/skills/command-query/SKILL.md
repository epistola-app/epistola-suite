---
name: command-query
description: Add a CQRS command or query to epistola-core. Use when adding a business operation (state change) or a read, including the authorization marker every message needs.
---

Every domain operation is a `Command<R>` or `Query<R>` dispatched through the mediator. There is one
shape, used by all ~280 handlers, and three build guards that reject anything else.

## Exemplars — copy these, don't copy this file

| For                       | Read                                                                                                  |
| ------------------------- | ----------------------------------------------------------------------------------------------------- |
| A command                 | `modules/epistola-core/src/main/kotlin/app/epistola/suite/environments/commands/CreateEnvironment.kt` |
| A query                   | `modules/epistola-core/src/main/kotlin/app/epistola/suite/environments/queries/ListEnvironments.kt`   |
| Authorization markers     | `modules/epistola-core/src/main/kotlin/app/epistola/suite/security/Authorized.kt`                     |
| A migration to go with it | [`docs/migrations.md`](../../../docs/migrations.md)                                                   |

## The shape

- **One file** holds the message and its handler: `data class X(...) : Command<R>, RequiresPermission`
  followed by `@Component class XHandler(private val jdbi: Jdbi) : CommandHandler<X, R>`. Name the
  handler `<Message>Handler`. Nothing registers it by hand — `SpringMediator` resolves handlers from
  the context by generic type.
- **Authorization is mandatory.** Implement exactly one marker from `Authorized`:
  - `RequiresPermission` — a `Permission` plus the `tenantKey` it applies to (the common case).
  - `RequiresPlatformRole` — cross-tenant operations.
  - `RequiresAuthentication` — any signed-in user.
  - `SystemInternal` — background work with no user; say in a comment why it bypasses checks.
- **Validation goes in `init {}`** on the message, via
  `validate("field", condition, code) { "message" }`. The `code` is a `ValidationCode` and defaults
  to `GENERIC`; give a real one for a new error condition, because the REST layer maps it into the
  RFC 9457 problem body. Use `FieldLimits` constants rather than literal lengths. `require(...)`
  throws the wrong exception type — it is not validation.
- **Data access lives in the handler**, with raw JDBI. Commands run in one transaction already, so
  don't add `@Transactional`; implement `SelfManagedTransaction` when you genuinely need to manage it.
- **Audit columns**: bind `created_by` / `updated_by` from `currentUserIdOrNull()?.value`.
- **Dispatch** with the extensions `X(...).execute()` and `X(...).query()` — not `mediator.send(...)`.
- **Time** comes from `EpistolaClock`, never `Instant.now()`. Database-owned timestamps stay `NOW()`.

## Steps

1. Put the file under the owning domain's `commands/` or `queries/` package in `epistola-core`.
2. Write the message: constructor args, the `Authorized` marker, `init {}` validation.
3. Write the handler: `@Component`, inject `Jdbi`, do the work, return the result type.
4. If it needs schema, add a migration per [`docs/migrations.md`](../../../docs/migrations.md) —
   module-owned, timestamped, forward-only.
5. Add a test that dispatches the real message; seed state through commands, not SQL
   ([`docs/testing.md`](../../../docs/testing.md)).
6. Consider the other surfaces: REST (`modules/rest-api`) and MCP (`modules/epistola-mcp`) may need
   the same capability, or an explicit decision that they don't.

## Verify

```bash
./gradlew :modules:epistola-core:integrationTest --tests "*YourTest*"
./gradlew :apps:epistola:unitTest --tests "app.epistola.suite.architecture.*"
```

The guards that will fail you: `MediatorWiringTest` (one handler per message, `@Component` present,
`Authorized` implemented), `AuthorizationCoverageTest` (same, per package),
`DomainBoundaryTest` (no importing another domain's handler), `ApplicationClockUsageTest`.
