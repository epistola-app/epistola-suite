---
name: command-query
description: Create a new CQRS command or query with handler. Use when adding new business operations (state changes) or data retrieval logic.
---

Create a new CQRS command or query in the epistola-core module.

**Input**: The operation name, which domain it belongs to, and what it does.

## Decision Points

Ask the user (if not already specified):
- Is this a **command** (state change) or a **query** (read-only)?
- Which domain? (tenants, templates, environments, documents, attributes, themes)
- What are the input parameters and return type?
- Does it need a transaction (`inTransaction`) or is a single statement enough?
- Any validation rules?

## Command Patterns

All commands live in `modules/epistola-core/src/main/kotlin/app/epistola/suite/<domain>/commands/`.
Queries live in `.../queries/`. Sub-packages are used for deeper nesting (e.g., `commands/variants/`, `queries/versions/`).

### Basic Command (INSERT)

**Reference**: `DeleteEnvironment.kt` — `modules/epistola-core/.../environments/commands/DeleteEnvironment.kt`

```kotlin
data class CreateEntity(
    val id: EntityId,
    val tenantId: TenantId,
    val name: String,
) : Command<Entity> {
    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= 100) { "Name must be 100 characters or less" }
    }
}

@Component
class CreateEntityHandler(private val jdbi: Jdbi) : CommandHandler<CreateEntity, Entity> {
    override fun handle(command: CreateEntity): Entity =
        executeOrThrowDuplicate("entity", command.id.value) {
            jdbi.withHandle<Entity, Exception> { handle ->
                handle.createQuery("""
                    INSERT INTO entities (id, tenant_id, name, created_at)
                    VALUES (:id, :tenantId, :name, NOW())
                    RETURNING *
                """)
                    .bind("id", command.id)
                    .bind("tenantId", command.tenantId)
                    .bind("name", command.name)
                    .mapTo<Entity>()
                    .one()
            }
        }
}
```

**`executeOrThrowDuplicate`**: Wraps INSERT statements only. Catches unique constraint violations and throws `DuplicateIdException`. Do NOT use for UPDATE/DELETE.

### Transactional Command (multi-statement)

**Reference**: `CreateDocumentTemplate.kt` — `modules/epistola-core/.../templates/commands/CreateDocumentTemplate.kt`

Use `jdbi.inTransaction` when the command performs multiple database operations that must succeed or fail together:

```kotlin
override fun handle(command: CreateDocumentTemplate): DocumentTemplate =
    executeOrThrowDuplicate("template", command.id.value) {
        jdbi.inTransaction<DocumentTemplate, Exception> { handle ->
            // 1. Insert the template
            val template = handle.createQuery("INSERT INTO ... RETURNING *")...
            // 2. Create default variant
            handle.createUpdate("INSERT INTO ...").execute()
            // 3. Create draft version
            handle.createUpdate("INSERT INTO ...").execute()
            template
        }
    }
```

### Nullable Return (`Command<R?>`)

**Reference**: `UpdateEnvironment.kt` — `modules/epistola-core/.../environments/commands/UpdateEnvironment.kt`

Return nullable when the entity might not exist:

```kotlin
data class UpdateEntity(...) : Command<Entity?>

override fun handle(command: UpdateEntity): Entity? =
    jdbi.withHandle<Entity?, Exception> { handle ->
        handle.createQuery("UPDATE ... RETURNING *")
            ...
            .mapTo<Entity>()
            .findOne()
            .orElse(null)
    }
```

### Boolean Return (delete)

**Reference**: `DeleteEnvironment.kt` — `modules/epistola-core/.../environments/commands/DeleteEnvironment.kt`

```kotlin
data class DeleteEntity(...) : Command<Boolean>

override fun handle(command: DeleteEntity): Boolean =
    jdbi.withHandle<Boolean, Exception> { handle ->
        val rowsAffected = handle.createUpdate("DELETE FROM ... WHERE ...")
            .bind(...)
            .execute()
        rowsAffected > 0
    }
```

### Result Wrapper

**Reference**: `PublishToEnvironment.kt` — `modules/epistola-core/.../templates/commands/versions/PublishToEnvironment.kt`

When a command returns multiple related objects:

```kotlin
data class PublishResult(
    val version: TemplateVersion,
    val activation: EnvironmentActivation,
    val newDraft: TemplateVersion? = null,
)

data class PublishToEnvironment(...) : Command<PublishResult?>
```

## Query Patterns

### List Query (with optional search)

```kotlin
data class ListEntities(
    val tenantId: TenantId,
    val searchTerm: String? = null,
) : Query<List<Entity>>
```

### Single-Entity Query (nullable)

```kotlin
data class GetEntity(
    val tenantId: TenantId,
    val id: EntityId,
) : Query<Entity?>
```

## Validation

There are 4 validation approaches, used in different places:

1. **`validate()` in `init` block** — Input validation (field format, required fields). Throws `ValidationException`.
   ```kotlin
   init {
       validate("name", name.isNotBlank()) { "Name is required" }
   }
   ```

2. **`require()` in handler** — Precondition checks (domain invariants).
   ```kotlin
   override fun handle(command: Cmd): Result {
       require(command.versionId.value > 0) { "Invalid version" }
   }
   ```

3. **Handler body validation** — Business rule checks that need DB lookups.
   ```kotlin
   val existing = handle.createQuery("SELECT ...").findOne().orElse(null)
       ?: throw SomeDomainException("Not found")
   ```

4. **Cross-cutting** — `executeOrThrowDuplicate` for INSERT uniqueness.

## Domain Model

Models live in `modules/epistola-core/src/main/kotlin/app/epistola/suite/<domain>/`. A `model/` subdirectory is used for larger domains (e.g., `templates/model/`).

```kotlin
data class Entity(
    val id: EntityId,
    val tenantId: TenantId,
    val name: String,
    val createdAt: Instant,
)
```

JDBI maps database columns to Kotlin data class properties automatically via `mapTo<>()`. Column `tenant_id` maps to `tenantId` (snake_case → camelCase).

## Database Migration

Create in `apps/epistola/src/main/resources/db/migration/V<next>__<description>.sql`:

```sql
CREATE TABLE entity_name (
    id         entity_slug PRIMARY KEY,
    tenant_id  tenant_slug NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE entity_name IS 'Description of what this table stores';
COMMENT ON COLUMN entity_name.id IS 'Unique slug identifier';
COMMENT ON COLUMN entity_name.tenant_id IS 'Owning tenant';
```

**Important**: `COMMENT ON` must be separate statements (not inside `CREATE TABLE`). Use domain types from existing migrations (e.g., `tenant_slug`, `template_slug`, `environment_slug`).

## Service Injection

When a command handler needs another service (not just JDBI):

```kotlin
@Component
class ComplexCommandHandler(
    private val jdbi: Jdbi,
    private val someService: SomeService,
) : CommandHandler<ComplexCommand, Result> { ... }
```

## Dispatch Pattern

Commands and queries are dispatched via extension functions:
```kotlin
CreateSomething(id = ..., tenantId = ...).execute()    // Command
ListSomething(tenantId = tenantId).query()             // Query
GetSomething(tenantId = tenantId, id = id).query()     // Query (nullable)
```

These require `MediatorContext` to be bound (automatic in handlers, explicit in tests via `withMediator { }`).

## Checklist

- [ ] Command/query data class + handler in `modules/epistola-core/.../`
- [ ] Domain model if new entity
- [ ] Database migration if new table
- [ ] Integration test (see `unit-test` skill)
- [ ] `./gradlew ktlintFormat`
- [ ] `./gradlew integrationTest`

## Gotchas

- Import `org.jdbi.v3.core.kotlin.mapTo` for the reified `mapTo<>()` extension
- Use `createQuery` (not `createUpdate`) for statements with `RETURNING`
- `executeOrThrowDuplicate` is for INSERTs only — don't wrap UPDATE/DELETE
- `validate()` is from `app.epistola.suite.validation.validate`
- Jackson 3 (`tools.jackson.*`) is used, not `com.fasterxml.jackson`
