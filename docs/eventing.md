# CQRS Eventing Architecture

## Overview

This document describes the evolution of the epistola-suite CQRS mediator pattern to support **command eventing**, **SQL boilerplate reduction**, and **distribution readiness**.

The architecture is inspired by fluxzero but adapted for our PostgreSQL + JDBI + Spring Boot model without requiring event sourcing.

## Key Design Decisions

- **All commands are events**: When a command completes successfully, the command instance is published as an event. Other components can subscribe to these events.
- **Two event timing modes**: Event handlers can run immediately (same transaction, failure propagates) or after commit (isolated, failure logged).
- **All events logged**: Every command is persisted to an `event_log` table for audit, debugging, and future replay.
- **SQL boilerplate via Kotlin extensions**: Reduce repetitive JDBI patterns with extension functions, not by hiding SQL.

## Architecture

### Command Execution Flow

```
HTTP Request
    ↓
Handler receives Command
    ↓
Mediator.send(command)
    ├→ CommandHandler.handle(command)
    │       ↓
    │   (may use @Transactional or jdbi.inTransaction)
    │       ↓
    │   Handler returns result
    │
    ├→ Invoke IMMEDIATE EventHandlers (same transaction)
    │   (if one fails, exception propagates back to caller)
    │
    ├→ Publish Spring event: CommandCompleted(command, result)
    │   ├→ AFTER_COMMIT EventHandlers invoke via Spring's @TransactionalEventListener
    │   │   (failures are caught and logged, don't affect command)
    │   ├→ EventLogSubscriber persists to event_log table
    │   └→ Any other @EventListener beans fire
    │
    ↓
Return result to caller
```

### Event Handler Timing

#### IMMEDIATE handlers
- Invoked immediately after `handler.handle()` returns
- Run within the same call stack
- If command uses `@Transactional`, they execute within that transaction
- If a handler throws an exception, it propagates to the caller (can roll back the command)
- Use case: Creating dependent entities (e.g., creating a default theme when a tenant is created)

```kotlin
@Component
class CreateDefaultThemeOnTenantCreate : EventHandler<CreateTenant> {
    override val phase = EventPhase.IMMEDIATE
    override fun on(event: CreateTenant, result: Any?) {
        val tenant = result as Tenant
        createDefaultTheme(tenant)  // if this fails, CreateTenant rolls back
    }
}
```

#### AFTER_COMMIT handlers
- Invoked after the command transaction commits via Spring's `@TransactionalEventListener`
- Exception-safe: failures are caught and logged, don't affect the command
- Use case: Cache invalidation, metrics, notifications, audit logging

```kotlin
@Component
class InvalidatePdfCache : EventHandler<PublishToEnvironment> {
    override val phase = EventPhase.AFTER_COMMIT  // default
    override fun on(event: PublishToEnvironment, result: Any?) {
        cache.invalidate(event.templateId, event.environmentId)
    }
}
```

Or using Spring's native listener:

```kotlin
@Component
class AuditLogger {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun log(event: CommandCompleted<*>) {
        auditService.log(event.command)
    }
}
```

## Implementation Phases

### Phase 1: Command Eventing

**Goal**: Every command publishes as an event; other components react to completed commands.

**Key additions**:
- `CommandCompleted<C>` — Spring event wrapper
- `EventPhase` enum — IMMEDIATE vs AFTER_COMMIT
- `EventHandler<E>` interface — Typed event subscription
- `TenantScoped` interface — Generic tenant extraction for logging/routing
- Event log table — Append-only audit trail
- `EventLogSubscriber` — Persists all events after commit

**New files**:
- `mediator/CommandCompleted.kt`
- `mediator/EventPhase.kt`
- `mediator/EventHandler.kt`
- `mediator/EventLogSubscriber.kt`
- `common/TenantScoped.kt`
- `mediator/Routable.kt`
- `db/migration/V15__event_log.sql`

**Modified files**:
- `mediator/SpringMediator.kt` — Add event publication and EventHandler discovery
- ~25 command data classes — Add `TenantScoped`, `Routable` interfaces

**Lines of code added**: ~400 (mediator, event log, event handlers)

### Phase 2: JDBI Boilerplate Reduction

**Goal**: Reduce repetitive SQL patterns while keeping SQL explicit for complex operations.

**Key additions**:
- Kotlin extensions for `Jdbi.withHandle`, `Jdbi.inTransaction` (eliminate `<T, Exception>` noise)
- Tenant-scoped query helpers: `findByTenantAndId`, `listForTenant`, `deleteForTenant`, `existsForTenant`
- JSONB binding helper: `bindJsonb` for automatic serialization

**New files**:
- `config/JdbiExtensions.kt`
- `config/TenantQueries.kt`
- `config/JdbiJsonbSupport.kt`

**Modified files**:
- ~40 handler files — Adopt new JDBI patterns (gradual, batched by domain)

**Lines of code reduction**: ~200-300 removed across all handlers

**Before**:
```kotlin
override fun handle(query: GetTheme): Theme? = jdbi.withHandle<Theme?, Exception> { handle ->
    handle.createQuery("SELECT * FROM themes WHERE id = :id AND tenant_key = :tenantId")
        .bind("id", query.id).bind("tenantId", query.tenantId)
        .mapTo<Theme>().findOne().orElse(null)
}
```

**After**:
```kotlin
override fun handle(query: GetTheme): Theme? = jdbi.withHandle { handle ->
    handle.findByTenantAndId<Theme>("themes", query.tenantId, query.id)
}
```

### Phase 3: Distribution Readiness (Design Only)

**Goal**: Add routing primitives so the architecture is ready for horizontal scaling.

**Key additions**:
- `Routable` interface — Commands declare their routing key (usually tenant ID)

**No infrastructure changes yet.** When actual distribution is needed:
1. Add `pending_commands` table (similar to `document_generation_requests`)
2. Implement `CommandRouter` that routes commands to specific instances
3. Add consumer position tracking for event subscribers

The existing `JobPoller` with `SELECT FOR UPDATE SKIP LOCKED` already proves this pattern works.

## Event Types

### CommandCompleted<C>
Published to Spring's `ApplicationEventPublisher` after every successful command. Wraps the command and its result.

```kotlin
data class CommandCompleted<C : Command<*>>(
    val command: C,
    val result: Any?,
    val occurredAt: Instant = Instant.now(),
)
```

### Event Log Table
```sql
CREATE TABLE event_log (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,          -- e.g., "CreateTheme", "PublishToEnvironment"
    tenant_key VARCHAR(100),                    -- extracted from TenantScoped commands
    entity_id VARCHAR(255),                    -- optional: primary entity affected
    payload JSONB NOT NULL,                    -- serialized command
    occurred_at TIMESTAMPTZ NOT NULL,
    instance_id VARCHAR(255)                   -- which app instance processed it
);
```

## Interfaces

### EventPhase
```kotlin
enum class EventPhase {
    /** Runs immediately after handler returns, within same transaction. Failure propagates. */
    IMMEDIATE,
    /** Runs after commit via Spring's TransactionalEventListener. Failure is isolated. */
    AFTER_COMMIT,
}
```

### EventHandler<E>
```kotlin
interface EventHandler<E : Command<*>> {
    val phase: EventPhase get() = EventPhase.AFTER_COMMIT
    fun on(event: E, result: Any?)
}
```

Subscribe to a specific command type:
```kotlin
@Component
class OnThemeCreated : EventHandler<CreateTheme> {
    override fun on(event: CreateTheme, result: Any?) {
        // ...
    }
}
```

### TenantScoped
```kotlin
interface TenantScoped {
    val tenantId: TenantId
}
```

Enables generic tenant extraction for event logging, audit, and future routing. Add to tenant-scoped commands:
```kotlin
data class CreateTheme(...) : Command<Theme>, TenantScoped {
    override val tenantId: TenantId
}
```

### Routable
```kotlin
interface Routable {
    /** Commands with the same routing key are processed by the same instance. */
    val routingKey: String
}
```

Declare affinity for sharding (tenant-based):
```kotlin
data class CreateTheme(...) : Command<Theme>, TenantScoped, Routable {
    override val routingKey: String get() = tenantId.value
}
```

## JDBI Helpers

### Extensions
```kotlin
// Wraps Jdbi.withHandle without the <T, Exception> noise
inline fun <T> Jdbi.withHandle(crossinline block: (Handle) -> T): T

inline fun <T> Jdbi.inTransaction(crossinline block: (Handle) -> T): T

inline fun Jdbi.useHandle(crossinline block: (Handle) -> Unit)
```

### Tenant-Scoped Queries
```kotlin
// Find a single tenant-scoped entity
inline fun <reified T : Any> Handle.findByTenantAndId(
    table: String, tenantId: TenantId, id: Any, columns: String = "*"
): T?

// Find root entity by ID (not tenant-scoped)
inline fun <reified T : Any> Handle.findById(
    table: String, id: Any, columns: String = "*"
): T?

// List all entities for a tenant
inline fun <reified T : Any> Handle.listForTenant(
    table: String, tenantId: TenantId, orderBy: String = "created_at DESC"
): List<T>

// Check existence
fun Handle.existsForTenant(table: String, tenantId: TenantId, id: Any): Boolean

// Delete
fun Handle.deleteForTenant(table: String, tenantId: TenantId, id: Any): Boolean
```

### JSONB Binding
```kotlin
fun Update.bindJsonb(name: String, value: Any?, objectMapper: ObjectMapper): Update

fun Query.bindJsonb(name: String, value: Any?, objectMapper: ObjectMapper): Query
```

## Testing

### Unit Tests (SpringMediator)
```kotlin
@Test
fun commandPublishesEvent() {
    val result = mediator.send(CreateTheme(...))
    verify(eventPublisher).publishEvent(any<CommandCompleted<*>>())
}

@Test
fun immediateHandlerExceptionPropagates() {
    // handler throws → caller sees the exception
}

@Test
fun afterCommitHandlerExceptionDoesNotPropagate() {
    // handler throws → exception logged, caller unaffected
}
```

### Integration Tests
```kotlin
@Test
fun eventLogReceivesAllCommands() {
    mediator.send(CreateTheme(...))
    val events = jdbi.withHandle { h ->
        h.createQuery("SELECT * FROM event_log").mapTo<EventLogEntry>().list()
    }
    assertThat(events).hasSize(1)
}

@Test
fun transactionalEventListenerFiresAfterCommit() {
    // verify listener is called only after transaction commits
}
```

### Handler Migration Tests

When migrating handlers to use JDBI helpers, verify identical behavior:
```kotlin
// Before and after should return same results for same inputs
val old = getThemeOld(tenantId, themeId)
val new = getThemeNew(tenantId, themeId)
assertThat(old).isEqualTo(new)
```

## Example: Adding an Event Subscriber

To react to a command, create an `EventHandler`:

```kotlin
@Component
class OnEnvironmentPublished : EventHandler<PublishToEnvironment> {
    override val phase = EventPhase.AFTER_COMMIT  // run after commit

    override fun on(event: PublishToEnvironment, result: Any?) {
        invalidatePdfCache(event.templateId, event.environmentId)
        metrics.increment("templates.published")
    }
}
```

Or use Spring's generic listener:

```kotlin
@Component
class CommandMetrics {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun recordCommand(event: CommandCompleted<*>) {
        metrics.timer("command.${event.command::class.simpleName}")
            .record(Duration.between(event.occurredAt, Instant.now()))
    }
}
```

## FAQ

### When should I use IMMEDIATE vs AFTER_COMMIT?

**IMMEDIATE**: When the event handler's failure should roll back the command. Examples:
- Creating dependent entities that must exist
- Validating invariants

**AFTER_COMMIT**: When the event is a side effect that can be retried independently. Examples:
- Cache invalidation
- Sending notifications
- Audit logging
- Metrics

### Do I have to add TenantScoped to all commands?

No. Only tenant-scoped commands (those with a `tenantId` property) should implement it. Root-level commands (CreateTenant, DeleteTenant) don't need it.

### Why not use full event sourcing?

Event sourcing is powerful but a significant architectural shift. Our model uses:
- **Direct state storage** (PostgreSQL tables as the source of truth)
- **Event logging** (append-only audit trail, not authoritative)
- **Command-as-event** (loose coupling via events, but state stored directly)

This hybrid approach keeps the pragmatism of a traditional CRUD system while gaining the benefits of an event-driven architecture for observability and eventual distribution.

### Can I use this without understanding all three phases?

Yes. Phases 1 and 2 are independent:
- Phase 1 (eventing) is the architecture change
- Phase 2 (JDBI helpers) is purely mechanical cleanup

Phase 3 (routing keys) is design-only and can be deferred.

## Migration Checklist

- [ ] Write this design document to `docs/eventing.md` ✓
- [ ] Implement Phase 1
  - [ ] Create `CommandCompleted`, `EventPhase`, `EventHandler`, `TenantScoped`, `Routable`
  - [ ] Modify `SpringMediator` for event publication
  - [ ] Create event log migration
  - [ ] Create `EventLogSubscriber`
  - [ ] Add interfaces to ~25 commands
  - [ ] Write unit/integration tests
- [ ] Implement Phase 2 (gradual)
  - [ ] Create JDBI helper files
  - [ ] Migrate handlers by domain (batch 1-2 domains per commit)
  - [ ] Run integration tests after each batch
- [ ] Phase 3 (deferred until distribution needed)
  - [ ] Add `Routable` alongside `TenantScoped`