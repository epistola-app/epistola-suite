# Architecture Analysis: Command Pattern with Event-Driven Extensions

## Executive Summary

**Recommendation: Keep current architecture foundation, add event-driven patterns incrementally.**

After comprehensive analysis of the Epistola Suite codebase and your requirements for event sourcing, workflow orchestration, and saga patterns, the recommendation is:

1. **Preserve** the current CQRS-lite pattern with direct SQL (it's a solid foundation)
2. **Add** event-driven capabilities incrementally using PostgreSQL as the backbone
3. **Introduce** hexagonal concepts ONLY where event sourcing/sagas require them
4. **Avoid** big-bang refactoring - build new patterns alongside existing code

Your requirements (event sourcing, workflows, sagas, guaranteed delivery) represent a significant architectural evolution beyond simple CRUD. This plan outlines how to achieve this while leveraging your PostgreSQL-centric architecture.

## Your Specific Concerns Addressed

### 1. Multi-Instance Readiness (2-5 instances)

**Status: ✅ Production Ready**

Your codebase **already implements the correct patterns** for safe multi-instance deployment:

**Job Distribution** (`apps/epistola/src/main/kotlin/app/epistola/suite/documents/batch/JobPoller.kt:73-111`):
- Uses PostgreSQL `FOR UPDATE SKIP LOCKED` for atomic job claiming
- Only one instance can claim each job (database-level guarantee)
- Instance ID tracking with hostname-pid combination
- No race conditions possible

**Failure Recovery** (`apps/epistola/src/main/kotlin/app/epistola/suite/documents/batch/StaleJobRecovery.kt`):
- Automatic recovery of stale jobs after 10 minutes
- Handles instance crashes gracefully
- Configurable timeout via `epistola.generation.polling.stale-timeout-minutes`

**Concurrency Control**:
- Explicit transaction boundaries via `jdbi.inTransaction<>` blocks
- Virtual threads (JDK 25) with ScopedValue for safe concurrent execution
- Semaphore limiting per instance (`epistola.generation.async.concurrency: 10`)

**Verdict**: The direct SQL approach does NOT hinder multi-instance scaling. PostgreSQL's ACID guarantees + explicit transactions provide all necessary safety.

### 2. Testability

**Status: ✅ Excellent**

Direct SQL + Testcontainers provides superior testability compared to repository abstractions:
- Tests verify real database behavior (not mocked repository behavior)
- Catches SQL errors, constraint violations, schema mismatches
- No need to maintain both "unit tests with mocks" and "integration tests"

### 3. Maintainability

**Status: ✅ Strong**

All SQL visible in handlers provides transparency:
- No hidden queries, no N+1 problems
- Easy to debug: copy SQL directly into psql for testing
- Performance issues immediately obvious

### 4. Best Practices / Modern Architecture

**Status: ✅ Follows Best Practices**

Your architecture demonstrates modern patterns:
- CQRS-lite with mediator
- Explicit transaction control
- PostgreSQL-optimized (JSONB, CTEs, `FOR UPDATE SKIP LOCKED`)
- Virtual threads + ScopedValue (JDK 25)

## Event-Driven Architecture Requirements

Based on your answers, you need:

**Eventing:**
- ✅ Domain events (notify on state changes)
- ✅ Event sourcing (events as source of truth)
- ✅ Inter-service events (future service boundaries)
- ✅ Audit trail (compliance/debugging)

**Durable Execution:**
- ✅ Workflow orchestration (long-running processes)
- ✅ Saga pattern (distributed transactions with compensation)
- ✅ Retry mechanisms (automatic failure recovery)
- ✅ Guaranteed delivery (at-least-once processing)

**Messaging:**
- ❓ Not sure yet (need guidance on PostgreSQL patterns)

## Recommended Implementation Path

### Phase 1: Event Foundation (Weeks 1-2)

#### 1.1 Implement Transactional Outbox Pattern

**Purpose**: Guaranteed delivery of events with exactly-once semantics

**Schema** (`apps/epistola/src/main/resources/db/migration/V10__create_outbox.sql`):
```sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_data JSONB NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at)
WHERE published_at IS NULL;
```

**Benefits**:
- Events and state changes in same transaction (atomicity)
- Guaranteed delivery (at-least-once)
- Works across multiple instances (FOR UPDATE SKIP LOCKED)
- Foundation for audit trail, event sourcing, sagas

### Phase 2: Event Sourcing (Weeks 3-4)

**Only for aggregates with complex state transitions or audit requirements.**

**Schema**:
```sql
CREATE TABLE event_store (
    sequence_number BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    version INT NOT NULL,  -- Optimistic concurrency
    event_type VARCHAR(100) NOT NULL,
    event_data JSONB NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (aggregate_type, aggregate_id, version)
);
```

**Benefits**:
- Complete audit trail (every state change is an event)
- Time travel (rebuild state at any point)
- Event replay (fix bugs by replaying events with new logic)

### Phase 3: Saga Pattern (Weeks 5-6)

**For distributed transactions with compensation.**

**Schema**:
```sql
CREATE TYPE saga_status AS ENUM ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'COMPENSATING', 'COMPENSATED', 'FAILED');

CREATE TABLE sagas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    saga_type VARCHAR(100) NOT NULL,
    status saga_status NOT NULL DEFAULT 'PENDING',
    current_step INT NOT NULL DEFAULT 0,
    max_step INT NOT NULL,
    state JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE saga_steps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    saga_id UUID NOT NULL REFERENCES sagas(id),
    step_number INT NOT NULL,
    step_name VARCHAR(100) NOT NULL,
    status saga_status NOT NULL DEFAULT 'PENDING',
    command JSONB,
    result JSONB,
    compensation_command JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (saga_id, step_number)
);
```

**Benefits**:
- Distributed transactions with rollback (compensation)
- Automatic retry of failed steps
- Works across multiple instances

### Phase 4: Workflow Orchestration (Weeks 7-8)

Use existing saga infrastructure, add:
- Timeouts per step
- Human approvals (wait for external event)
- Scheduled triggers

### Phase 5: PostgreSQL Messaging (Weeks 9-10)

**Three options:**

1. **LISTEN/NOTIFY**: Real-time, low latency, no persistence
2. **Outbox + Polling**: Reliable, guaranteed delivery (already in Phase 1)
3. **Hybrid**: Both reliability and low latency

## Architectural Impact

### What Changes

1. **Handlers write events to outbox** (Phase 1)
    - Minimal change: Add `handle.publishToOutbox()` call
    - No impact on testability

2. **Event-sourced aggregates use event store** (Phase 2)
    - NEW handlers for event-sourced aggregates
    - Existing CRUD handlers unchanged

3. **Long-running operations use sagas** (Phase 3)
    - NEW saga orchestrator
    - Existing commands can be saga steps

### What Stays the Same

- ✅ Direct SQL in handlers (still appropriate for CRUD)
- ✅ JDBI with explicit transactions
- ✅ Mediator pattern
- ✅ Testcontainers for integration tests
- ✅ Virtual threads + ScopedValue

### When to Use Each Pattern

| Use Case | Pattern | Example |
|----------|---------|---------|
| Simple CRUD | Current (direct SQL) | Create tenant, update theme |
| Audit trail needed | Outbox events | Template changes, user actions |
| Complex state history | Event sourcing | Document lifecycle |
| Distributed transaction | Saga | Multi-step document generation |
| Long-running process | Workflow | Document review & approval |
| Real-time notifications | LISTEN/NOTIFY | UI updates |

## Hexagonal Architecture: When and Where

**Verdict**: Introduce hexagonal concepts **only** where event sourcing/sagas require them.

### Where to Apply Hexagonal

**Domain Layer** (NEW):
```
apps/epistola/src/main/kotlin/app/epistola/suite/
├── domain/              # NEW - Domain logic for event-sourced aggregates
│   ├── template/
│   │   ├── TemplateAggregate.kt
│   │   ├── TemplateEvents.kt
│   │   └── TemplateRepository.kt (port)
│   └── document/
│       ├── DocumentAggregate.kt
│       └── DocumentEvents.kt
├── infrastructure/      # NEW - Implementations
│   ├── events/
│   │   ├── OutboxEventStore.kt
│   │   └── PostgresEventBus.kt
│   └── sagas/
│       └── JdbiSagaRepository.kt
└── templates/           # EXISTING - Keep as-is for CRUD
    ├── commands/
    └── queries/
```

### Where NOT to Apply Hexagonal

- ✅ Keep simple CRUD handlers as-is
- ✅ Keep query handlers as-is
- ✅ Keep job processing as-is

**Reasoning**: Don't refactor working code. Apply new patterns to new requirements.

## Implementation Strategy

### Incremental Migration Path

**Week 1-2**: Outbox events
- Add outbox table
- Add `publishToOutbox()` extension
- Update 2-3 critical handlers
- Implement OutboxPublisher
- Implement AuditTrailHandler

**Week 3-4**: Event sourcing (optional)
- Add event_store table
- Identify 1-2 aggregates for event sourcing
- Build event-sourced handler alongside existing handler
- Compare approaches, choose winner

**Week 5-6**: Saga pattern
- Add sagas tables
- Implement SagaOrchestrator
- Convert document generation to saga
- Test compensation logic

**Week 7-8**: Workflow orchestration
- Add workflow-specific fields
- Implement timeout handling
- Implement wait-for-event logic

**Week 9-10**: Messaging optimization
- Evaluate LISTEN/NOTIFY vs polling
- Implement chosen pattern
- Measure latency improvements

### Rollback Strategy

Each phase is independent:
- Outbox can be disabled via feature flag
- Event-sourced aggregates coexist with CRUD handlers
- Sagas optional for new features
- Messaging patterns can be A/B tested

## Recommendations

### Immediate Actions

1. **Read about patterns**:
    - Outbox: https://microservices.io/patterns/data/transactional-outbox.html
    - Event sourcing: https://martinfowler.com/eaaDev/EventSourcing.html
    - Saga: https://microservices.io/patterns/data/saga.html

2. **Start with Phase 1** (Outbox):
    - Simplest pattern
    - Immediate value (audit trail, guaranteed delivery)
    - Foundation for all other patterns

3. **Defer Phase 2** (Event Sourcing):
    - Most complex pattern
    - Only use if you need time travel
    - Start with 1 aggregate, evaluate before expanding

4. **Consider alternatives to custom implementation**:
    - Axon Framework (event sourcing + CQRS + sagas)
    - Eventuate (event sourcing + sagas)
    - Temporal (workflow orchestration)

## Critical Files (Reference Implementation)

These files demonstrate correct patterns:

- `apps/epistola/src/main/kotlin/app/epistola/suite/documents/batch/JobPoller.kt`
    - Multi-instance job claiming with `FOR UPDATE SKIP LOCKED`

- `apps/epistola/src/main/kotlin/app/epistola/suite/documents/batch/StaleJobRecovery.kt`
    - Automatic recovery from instance failures

- `apps/epistola/src/main/kotlin/app/epistola/suite/templates/commands/CreateDocumentTemplate.kt`
    - Multi-step transaction creating related entities

- `apps/epistola/src/main/kotlin/app/epistola/suite/mediator/SpringMediator.kt`
    - Handler discovery and dispatch

## Conclusion

**The current architecture is a solid foundation.** With event-driven requirements, you should:

1. **Keep** current CRUD approach (direct SQL works well)
2. **Add** event-driven patterns incrementally
3. **Introduce** hexagonal architecture only where needed
4. **Leverage** PostgreSQL as the backbone (outbox, event store, sagas)

Start with Phase 1 (Outbox) for immediate value. Evaluate each subsequent phase based on actual needs rather than assumed requirements.