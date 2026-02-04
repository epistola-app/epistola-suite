# Refactoring Entity IDs: From UUID to Slug Format

This document explains the architecture for entity IDs in Epistola Suite and how to change an entity ID from UUID-based to slug-based format (or vice versa).

## Architecture Overview

### Generic EntityId System

The codebase uses a type-safe entity ID system with the following hierarchy:

```kotlin
// Base interface for all typed entity IDs
sealed interface EntityId<T : EntityId<T, V>, V : Any> {
    val value: V
}

// Marker interface for slug-based IDs (String value)
sealed interface SlugId<T : SlugId<T>> : EntityId<T, String>

// Marker interface for UUID-based IDs
sealed interface UuidId<T : UuidId<T>> : EntityId<T, UUID>
```

### Current ID Types

| Entity ID | Type | Value | Example |
|-----------|------|-------|---------|
| `TenantId` | `SlugId` | String | `acme-corp` |
| `TemplateId` | `UuidId` | UUID | `550e8400-e29b-41d4-a716-446655440000` |
| `VariantId` | `UuidId` | UUID | UUID |
| `VersionId` | `UuidId` | UUID | UUID |
| `ThemeId` | `UuidId` | UUID | UUID |
| `EnvironmentId` | `UuidId` | UUID | UUID |
| `DocumentId` | `UuidId` | UUID | UUID |

## Changing an Entity ID Type

### Prerequisites

1. **Project is not in production** - Breaking changes are acceptable
2. **Delete local database** - You'll need to recreate the schema after migration changes

### Step-by-Step Process

#### 1. Update EntityId.kt

Location: `apps/epistola/src/main/kotlin/app/epistola/suite/common/ids/EntityId.kt`

Change the ID class from `UuidId` to `SlugId`:

```kotlin
// Before (UUID-based)
@JvmInline
value class MyEntityId(override val value: UUID) : UuidId<MyEntityId> {
    override fun toString(): String = value.toString()
    companion object {
        fun of(id: UUID): MyEntityId = MyEntityId(id)
        fun of(id: String): MyEntityId = MyEntityId(UUID.fromString(id))
        fun generate(): MyEntityId = MyEntityId(UUID.randomUUID())
    }
}

// After (Slug-based)
@JvmInline
value class MyEntityId(override val value: String) : SlugId<MyEntityId> {
    init {
        require(value.length in 3..63) { "ID must be 3-63 characters, got ${value.length}" }
        require(SLUG_PATTERN.matches(value)) { "ID must match slug pattern" }
        require(value !in RESERVED_WORDS) { "ID '$value' is reserved" }
    }
    override fun toString(): String = value
    companion object {
        private val SLUG_PATTERN = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
        private val RESERVED_WORDS = setOf("admin", "api", "www", "system", "internal", "null", "undefined")
        fun of(value: String): MyEntityId = MyEntityId(value)
        fun validateOrNull(value: String): MyEntityId? = runCatching { MyEntityId(value) }.getOrNull()
        // Note: No generate() function - slugs are client-provided
    }
}
```

Key differences:
- No `generate()` function for slugs (client-provided)
- Add validation in `init` block
- Add `validateOrNull()` for safe parsing

#### 2. Create JDBI Support (if first SlugId)

Location: `apps/epistola/src/main/kotlin/app/epistola/suite/config/JdbiSlugIdSupport.kt`

```kotlin
class SlugIdArgumentFactory : AbstractArgumentFactory<SlugId<*>>(Types.VARCHAR) {
    override fun build(value: SlugId<*>, config: ConfigRegistry): Argument =
        Argument { position, statement, _ -> statement.setString(position, value.value) }
}

class MyEntityIdColumnMapper : ColumnMapper<MyEntityId> {
    override fun map(r: ResultSet, columnNumber: Int, ctx: StatementContext): MyEntityId? {
        val value = r.getString(columnNumber)
        return if (r.wasNull()) null else MyEntityId.of(value)
    }
}
```

Register in `JdbiConfig.kt`:

```kotlin
registerArgument(SlugIdArgumentFactory())
registerColumnMapper(MyEntityId::class.java, MyEntityIdColumnMapper())
```

#### 3. Update Database Migrations

Modify the original migration files (since not in production):

```sql
-- Before
CREATE TABLE my_entity (
    id UUID PRIMARY KEY,
    ...
);

-- After
CREATE TABLE my_entity (
    id VARCHAR(63) PRIMARY KEY CHECK (id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    ...
);
```

Also update foreign key columns in related tables:

```sql
-- Before
my_entity_id UUID REFERENCES my_entity(id)

-- After
my_entity_id VARCHAR(63) REFERENCES my_entity(id)
```

**Important**: After modifying migrations, delete your local database and restart the app to apply fresh schema.

#### 4. Update OpenAPI Schemas

For DTOs that expose the ID:

```yaml
# Before
myEntityId:
  type: string
  format: uuid
  description: ID of the entity

# After
myEntityId:
  type: string
  pattern: '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'
  minLength: 3
  maxLength: 63
  description: Slug identifier of the entity
```

For path parameters:

```yaml
# Before
- name: myEntityId
  in: path
  required: true
  schema:
    type: string
    format: uuid

# After
- name: myEntityId
  in: path
  required: true
  schema:
    type: string
    pattern: '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'
    minLength: 3
    maxLength: 63
```

#### 5. Update API Controllers

Change parameter types from `UUID` to `String`:

```kotlin
// Before
override fun getEntity(entityId: UUID): ResponseEntity<EntityDto> {
    val entity = GetEntity(MyEntityId.of(entityId)).query()
    ...
}

// After
override fun getEntity(entityId: String): ResponseEntity<EntityDto> {
    val entity = GetEntity(MyEntityId.of(entityId)).query()
    ...
}
```

#### 6. Update Web Handlers

Change from `requirePathUuid` to `pathVariable`:

```kotlin
// Before
val entityId = request.requirePathUuid("entityId")
val entity = GetEntity(MyEntityId.of(entityId)).query()

// After
val entityId = request.pathVariable("entityId")
val entity = GetEntity(MyEntityId.of(entityId)).query()
```

#### 7. Update Query Handlers (ResultSet mapping)

```kotlin
// Before
MyEntityId(rs.getObject("entity_id", UUID::class.java))

// After
MyEntityId(rs.getString("entity_id"))
```

#### 8. Update Tests

Replace `MyEntityId.generate()` with explicit slugs:

```kotlin
// Before
val entityId = MyEntityId.generate()

// After
val entityId = MyEntityId.of("test-entity-slug")

// Or use a counter for unique slugs in tests
private var counter = 0
private fun nextSlug(): String = "test-entity-${++counter}"
```

#### 9. Update Thymeleaf Templates (if applicable)

Add slug input fields to forms:

```html
<div class="form-group">
    <label for="slug">Slug (ID)</label>
    <input type="text" id="slug" name="slug" required
           pattern="^[a-z][a-z0-9]*(-[a-z0-9]+)*$"
           minlength="3" maxlength="63"
           placeholder="my-entity-slug">
    <span class="form-hint">3-63 characters, lowercase letters, numbers, and hyphens only</span>
</div>
```

#### 10. Update CHANGELOG.md

Document the breaking change:

```markdown
## [Unreleased]

### Changed
- **BREAKING: MyEntityId changed from UUID to slug format**
  - Format: 3-63 lowercase characters, letters (a-z), numbers (0-9), and hyphens (-)
  - IDs must now be client-provided (no auto-generation)
  - Database: column changed from `UUID` to `VARCHAR(63)`
  - API: parameter changed from UUID to string with pattern validation
```

### Validation

After making changes, run:

```bash
# Format code
./gradlew ktlintFormat

# Build and run tests
./gradlew build

# If tests fail, check for:
# - Remaining generate() calls
# - UUID type mismatches
# - ResultSet mapping issues
```

## Slug Validation Rules

The standard slug pattern used in this codebase:

- **Length**: 3-63 characters
- **Characters**: lowercase letters (a-z), numbers (0-9), hyphens (-)
- **Must start with**: a letter
- **Cannot end with**: a hyphen
- **No consecutive hyphens**
- **Regex**: `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`
- **Reserved words**: `admin`, `api`, `www`, `system`, `internal`, `null`, `undefined`

This format is DNS subdomain compatible, suitable for use in URLs.

## Files Changed Summary

When refactoring an entity ID, expect to modify:

| Category | Files |
|----------|-------|
| Core | `EntityId.kt`, `JdbiConfig.kt` |
| Database | Migration files (`V*.sql`) |
| OpenAPI | Schema files, path files |
| API | Controllers, DTO mappers |
| Web | Handlers, Thymeleaf templates |
| Tests | Unit tests, integration tests, fixtures |
| Docs | `CHANGELOG.md` |
