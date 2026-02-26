# Composite Entity IDs Refactor - Complete Steps

## Overview

This refactor implements hierarchical composite entity identities for event sourcing and audit logging. The goal is for every entity to carry its full path-based address (e.g., `acme/invoice/nl/3`), eliminating redundant `tenantId` parameters throughout the codebase.

**Key Principle**: Models use composite IDs → Queries take composite IDs → Mapping layer constructs from DB rows

## Completed Work ✅

- [x] Database schema (V8) - Fixed composite primary keys
- [x] Renamed slug types to *Key (TemplateKey, VariantKey, etc.)
- [x] Created composite ID types in EntityId.kt
- [x] Extracted CompositeId base class - eliminates 45+ lines of boilerplate
- [x] Updated TemplateVariant model to use `id: VariantId`
- [x] Refactored GetVariant and GetVariantSummaries queries
- [x] Initial git commit with Phase 1 + improvements

## Phase 2: Model Updates

### Step 1: TemplateVersion Model
**File**: `modules/epistola-core/src/main/kotlin/app/epistola/suite/templates/model/TemplateVersion.kt`

**Current state**:
```kotlin
data class TemplateVersion(
    val version: Int,
    val tenantId: TenantId,
    val templateId: TemplateKey,
    val variantId: VariantKey,
    // ... other fields
)
```

**Target state**:
```kotlin
data class TemplateVersion(
    val id: VersionId,  // Composite: carries variantId → templateId → tenantId
    // ... other fields
)
```

**Implementation**:
1. Change `version: Int` to `id: VersionId`
2. Remove `tenantId`, `templateId`, `variantId` fields
3. Add convenience accessors:
   ```kotlin
   val tenantId: TenantId get() = id.tenantId
   val templateId: TemplateId get() = id.templateId
   val variantId: VariantId get() = id.variantId
   val version: Int get() = id.version
   ```

### Step 2: EnvironmentActivation Model
**File**: `modules/epistola-core/src/main/kotlin/app/epistola/suite/templates/model/EnvironmentActivation.kt`

**Target state**:
```kotlin
data class EnvironmentActivation(
    val variantId: VariantId,        // Composite
    val environmentId: EnvironmentId, // Composite
    val version: Int,                 // version number
    val activatedAt: OffsetDateTime,
) {
    val tenantId: TenantId get() = variantId.tenantId
    val templateId: TemplateId get() = variantId.templateId
    val environmentKey: EnvironmentKey get() = environmentId.key
}
```

**Notes**:
- Remove raw `tenantId`, `templateId` fields
- `environmentId` is now composite, carries `tenantId`
- The `version` field is just the Int number, not a composite VersionId

### Step 3: DocumentTemplate Model
**File**: `modules/epistola-core/src/main/kotlin/app/epistola/suite/templates/DocumentTemplate.kt`

**Target state**:
```kotlin
data class DocumentTemplate(
    val id: TemplateId,  // Composite: tenantId + key
    val name: String,
    val themeId: ThemeId, // Composite: tenantId + key
    // ... other fields
)
```

**Implementation**:
1. Change `id` from `TemplateKey` to `TemplateId`
2. Change `themeId` from `ThemeKey` to `ThemeId`
3. Add accessors if needed
4. Update any field that was `tenantId` - it's now in the composite IDs

## Phase 3: Query Updates

For each query, apply this pattern:

### Template Pattern: Query with Composite IDs

**Before**:
```kotlin
data class GetVariant(
    val tenantId: TenantId,
    val templateId: TemplateKey,
    val variantId: VariantKey,
) : Query<TemplateVariant?>

// Handler uses .mapTo<TemplateVariant>()
```

**After**:
```kotlin
data class GetVariant(
    val id: VariantId,  // Composite, carries all context
) : Query<TemplateVariant?>

// Handler uses custom .map { rs, _ -> ... } with manual construction
```

### Step 4: GetVersion Query
**File**: `modules/epistola-core/src/main/kotlin/app/epistola/suite/templates/queries/versions/GetVersion.kt`

Already updated. Verify it works with new TemplateVersion model.

### Step 5: GetDocumentTemplate Query
**File**: `modules/epistola-core/src/main/kotlin/app/epistola/suite/templates/queries/GetDocumentTemplate.kt`

**Update signature**:
```kotlin
data class GetDocumentTemplate(
    val id: TemplateId,  // Was: tenantId + id: TemplateKey
) : Query<DocumentTemplate?>
```

**Update handler**:
```kotlin
.map { rs, _ ->
    val tenantId = TenantId(rs.getString("tenant_key"))
    val templateKey = TemplateKey(rs.getString("id"))
    val templateId = TemplateId(tenantId, templateKey)

    val themeKey = ThemeKey(rs.getString("theme_key"))
    val themeId = ThemeId(tenantId, themeKey)

    DocumentTemplate(
        id = templateId,
        name = rs.getString("name"),
        themeId = themeId,
        // ... map other fields
    )
}
```

### Step 6: GetActiveVersion Query
**File**: `modules/epistola-core/src/main/kotlin/app/epistola/suite/templates/queries/activations/GetActiveVersion.kt`

**Update signature**:
```kotlin
data class GetActiveVersion(
    val variantId: VariantId,        // Was: tenantId + templateId + variantId
    val environmentKey: EnvironmentKey, // Composite environment lookup
) : Query<TemplateVersion?>
```

**Update handler binding**:
```kotlin
.bind("variantId", query.variantId.key.value)
.bind("templateId", query.variantId.templateId.key.value)
.bind("tenantId", query.variantId.tenantId.value)
.bind("environmentId", query.environmentKey.value)
```

### Step 7: List Queries (GetDocumentMetadata, ListDocuments, etc.)
Apply same pattern to all queries that return models with composite IDs:
- Update query parameters to use composite IDs
- Update `.mapTo<>()` to custom `.map { rs, _ -> ... }` blocks
- Construct composite IDs in row mappers

**Files to update**:
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/documents/queries/GetDocumentMetadata.kt`
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/documents/queries/ListDocuments.kt`
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/templates/queries/activations/ListActivations.kt`
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/templates/queries/activations/GetDeploymentMatrix.kt`
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/templates/queries/variants/ListVariants.kt`

## Phase 4: Command Updates

### Step 8: Update Command Signatures
For commands that currently take individual parameters, collapse to composite IDs:

**Example: CreateVersion**
```kotlin
// Before
data class CreateVersion(
    val tenantId: TenantId,
    val templateId: TemplateKey,
    val variantId: VariantKey,
    val templateModel: TemplateDocument,
) : Command<TemplateVersion?>

// After
data class CreateVersion(
    val variantId: VariantId,  // Composite
    val templateModel: TemplateDocument,
) : Command<TemplateVersion?>
```

**Files to update** (all under `modules/epistola-core/src/main/kotlin/app/epistola/suite/templates/commands/`):
- `variants/CreateVariant.kt` - Already uses VariantKey, update to pass to queries properly
- `versions/CreateVersion.kt`
- `versions/UpdateDraft.kt`
- `versions/UpdateVersion.kt`
- `versions/PublishToEnvironment.kt`
- `versions/ArchiveVersion.kt`
- `activations/RemoveActivation.kt`
- `UpdateDocumentTemplate.kt`
- `ImportTemplates.kt`

**In each command handler**:
- Update SQL INSERT statements to bind raw fields from the composite ID
- Example: When inserting to template_versions, extract `tenantId`, `templateId`, `variantId` from the `variantId: VariantId` composite

### Step 9: Update CreateVariant Handler
**File**: `modules/epistola-core/src/main/kotlin/app/epistola/suite/templates/commands/variants/CreateVariant.kt`

This needs to work with updated GetVariant query signature. The handler receives a composite VariantId, creates a variant, then calls GetVariant with the new composite ID.

```kotlin
// After creating the variant, construct composite ID:
val templateId = TemplateId(command.tenantId, command.templateId)
val variantId = VariantId(templateId, command.id)
// Now variantId can be used with updated queries
```

## Phase 5: Handler Updates

### Step 10: Update HTTP Handlers
**Files** (under `apps/epistola/src/main/kotlin/app/epistola/suite/handlers/`):
- `VariantRouteHandler.kt`
- `VersionRouteHandler.kt`
- `DocumentTemplateHandler.kt`

**Pattern**: Extract path variables, construct composite IDs, pass to queries

```kotlin
fun handleGetVariant(request: ServerRequest): ServerResponse {
    val tenantId = TenantId.of(request.pathVariable("tenantId"))
    val templateKey = TemplateKey.validateOrNull(request.pathVariable("templateId"))!!
    val variantKey = VariantKey.validateOrNull(request.pathVariable("variantId"))!!

    // Construct composite ID
    val variantId = VariantId(TemplateId(tenantId, templateKey), variantKey)

    // Call query with composite ID
    val variant = mediator.query(GetVariant(id = variantId))
    // ...
}
```

### Step 11: Add Helper Extension Functions
**File**: `apps/epistola/src/main/kotlin/app/epistola/suite/htmx/HtmxDsl.kt`

Add convenience functions for common handler patterns:

```kotlin
fun ServerRequest.tenantId(): TenantId = TenantId.of(pathVariable("tenantId"))

fun ServerRequest.templateId(tenantId: TenantId): TemplateId {
    val key = TemplateKey.validateOrNull(pathVariable("templateId"))!!
    return TemplateId(tenantId, key)
}

fun ServerRequest.variantId(templateId: TemplateId): VariantId {
    val key = VariantKey.validateOrNull(pathVariable("variantId"))!!
    return VariantId(templateId, key)
}

fun ServerRequest.versionId(variantId: VariantId): VersionId {
    val version = pathVariable("versionId").toInt()
    return VersionId(variantId, version)
}
```

## Phase 6: Theme/Environment/Attribute Domains

### Step 12: Repeat Pattern for Other Domains

Apply the same refactor to themes, environments, and attributes:

**Theme Domain**:
- Model: `Theme` → use `id: ThemeId` (composite)
- Query: `GetTheme` → takes `id: ThemeId`
- Commands: Use `id: ThemeId`

**Environment Domain**:
- Model: `Environment` → use `id: EnvironmentId` (composite)
- Query: `GetEnvironment` → takes `id: EnvironmentId`
- Commands: Use `id: EnvironmentId`

**Attribute Domain**:
- Model: `VariantAttributeDefinition` → use `id: AttributeId` (composite)
- Query: `GetAttributeDefinition` → takes `id: AttributeId`
- Commands: Use `id: AttributeId`

## Phase 7: EntityIdentifiable and Event Log

### Step 13: Wire Composite IDs into Audit Logs
**File**: `modules/epistola-core/src/main/kotlin/app/epistola/suite/common/EntityIdentifiable.kt`

Update entities to return full path:

```kotlin
// TemplateVariant
override val entityId: String get() = id.toPath()

// TemplateVersion
override val entityId: String get() = id.toPath()

// Theme, Environment, etc.
override val entityId: String get() = id.toPath()
```

## Testing

### Step 14: Run Tests

```bash
# Fast unit tests
./gradlew unitTest

# Full integration tests with DB
./gradlew integrationTest

# All tests including UI
./gradlew test
```

**Expected outcomes**:
- Compilation succeeds
- All tests pass
- No changes to external API contracts

### Step 15: Manual Verification

1. **Create a template**:
   ```bash
   POST /tenants/acme/templates
   ```

2. **Add variants with same slug in different templates**:
   ```bash
   POST /tenants/acme/templates/invoice/variants
   POST /tenants/acme/templates/receipt/variants (same slug, should succeed)
   ```

3. **Verify two variants with same slug in same template fails**:
   ```bash
   POST /tenants/acme/templates/invoice/variants (same slug, should fail)
   ```

4. **Verify event log contains full paths**:
   ```sql
   SELECT entity_id, event_name FROM event_log LIMIT 5;
   -- Expected: entity_id = "acme/invoice/nl/3", etc.
   ```

## Code Style & Cleanup

### Step 16: Format and Check
```bash
./gradlew ktlintFormat
./gradlew ktlintCheck
```

### Step 17: Final Commit
```bash
git add -A
git commit -m "refactor: complete composite entity IDs implementation (Phase 2)

- All models use composite IDs (VariantId, VersionId, TemplateId, etc.)
- Queries simplified to take single composite ID instead of 4 parameters
- Row mappers construct composite IDs from database columns
- Commands updated to use composite IDs
- Handlers construct composite IDs from path variables
- EntityIdentifiable returns full path for audit logs
- Database schema enforces composite primary keys
- All tests passing, code style validated

Breaking changes:
- Query signatures changed to use composite IDs
- Models no longer carry redundant tenantId/templateId fields

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

## Estimated Effort

| Phase | Files | Effort |
|-------|-------|--------|
| Phase 1 (Done) | 15 | ~2 hrs |
| Phase 2 (Models) | 3 | ~1 hr |
| Phase 3 (Queries) | 8 | ~3 hrs |
| Phase 4 (Commands) | 10 | ~3 hrs |
| Phase 5 (Handlers) | 5 | ~2 hrs |
| Phase 6 (Other domains) | 15 | ~5 hrs |
| Phase 7 (Audit logs) | 3 | ~1 hr |
| Testing & cleanup | - | ~2 hrs |
| **Total** | **59** | **~19 hrs** |

**Parallelizable**: Phases 2-6 can be done in parallel by domain (templates, themes, environments, attributes)

## Architecture Improvements

### CompositeId Base Class
All composite ID types extend the `CompositeId` abstract base class, which provides:
- `toPath(): String` - Serialization to slash-separated format
- `toString(): String` - Returns `toPath()` by default
- Single source of truth for composite ID behavior

This eliminates duplicated boilerplate across all ID types and makes it easy to add common functionality in the future.

**Example**:
```kotlin
abstract class CompositeId {
    abstract fun toPath(): String
    override fun toString(): String = toPath()
}

data class TemplateId(val tenantId: TenantId, val key: TemplateKey) : CompositeId() {
    override fun toPath(): String = "${tenantId.value}/${key.value}"
    // ... rest of implementation
}
```

## Key Patterns Reference

### Row Mapper Pattern
```kotlin
.map { rs, _ ->
    val tenantId = TenantId(rs.getString("tenant_key"))
    val key = TemplateKey(rs.getString("id"))
    val id = TemplateId(tenantId, key)

    MyEntity(
        id = id,
        // ... other fields
    )
}
```

### Query Signature Pattern
```kotlin
data class GetMyEntity(val id: MyId) : Query<MyEntity?>
```

### Command Pattern
```kotlin
data class CreateMyEntity(
    val id: MyId,
    val field1: String,
) : Command<MyEntity?>
```

### Handler Pattern
```kotlin
val id = MyId(
    TenantId.of(request.pathVariable("tenantId")),
    MyKey.validateOrThrow(request.pathVariable("myId"))
)
val entity = mediator.query(GetMyEntity(id = id))
```

## Rollback Plan

If issues arise:
1. Keep current commit - it's a stable checkpoint
2. The refactor is 100% backwards compatible at DB level
3. Can incrementally merge phases

## Questions & Issues

### "Should model X use composite ID?"
**Answer**: Yes, if it has a unique key at a specific hierarchy level. Examples:
- ✅ TemplateVariant - unique per (tenant, template)
- ✅ Theme - unique per tenant
- ❌ TemplateVersion - not directly addressable; accessed via VariantId

### "What about existing code calling the old signatures?"
**Answer**: Update those call sites to construct the composite ID first (like the handler pattern above).

### "Does this affect the REST API contract?"
**Answer**: No. Path variables stay the same (`/tenants/{tenantId}/templates/{templateId}/variants/{variantId}`). Internal implementation changes only.
