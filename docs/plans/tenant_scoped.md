# Tenant-Scoped Composite Primary Keys

## Context

Entity IDs (`document_templates`, `template_variants`, `environments`) currently use single-column global primary keys. This means two tenants cannot use the same slug (e.g., both wanting a template called "invoice"). This refactor changes all tenant-owned entities to use composite PKs `(tenant_id, id)`, allowing ID reuse across tenants.

**Prerequisite for**: [Explicit Default Variant Flag](./default_variant.md)

## Design Decisions

- **In-place migration changes**: Modify V2, V3, V5, V11 directly (project is pre-production)
- **Delete V12 + V13**: Merge `variant_attribute_definitions` into V3, `tags → attributes` rename applied directly in V3
- **Propagate tenant_id**: Every table referencing a tenant-scoped entity gets its own `tenant_id` column for composite FKs
- **Domain models**: Only add `tenantId` to Kotlin models where it wasn't already present (`TemplateVariant`). `TemplateVersion`, `VersionSummary`, and `VariantSummary` do NOT need it — tenant_id is always known from context and doesn't need to travel through the model. The DB column exists for FK integrity, not application logic.
- **Simpler queries**: Many JOINs that existed solely for tenant isolation (e.g., `JOIN document_templates dt ON ... WHERE dt.tenant_id = ...`) become direct WHERE clauses on the table's own `tenant_id` column.

---

## Step 1: Migration Changes

### V2: `document_templates`

```sql
-- BEFORE
CREATE TABLE document_templates (
    id VARCHAR(50) PRIMARY KEY CHECK (...),
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    ...
);
CREATE INDEX idx_document_templates_tenant_id ON document_templates(tenant_id);

-- AFTER
CREATE TABLE document_templates (
    id VARCHAR(50) NOT NULL CHECK (...),
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    ...
    PRIMARY KEY (tenant_id, id)
);
-- Drop idx_document_templates_tenant_id (tenant_id is now the leading PK column)
```

### V3: `environments`

```sql
-- BEFORE
CREATE TABLE environments (
    id VARCHAR(30) PRIMARY KEY CHECK (...),
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    ...
);

-- AFTER
CREATE TABLE environments (
    id VARCHAR(30) NOT NULL CHECK (...),
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    ...
    PRIMARY KEY (tenant_id, id)
);
-- Drop idx_environments_tenant_id (tenant_id is now the leading PK column)
```

### V3: `template_variants`

```sql
-- BEFORE
CREATE TABLE template_variants (
    id VARCHAR(50) PRIMARY KEY CHECK (...),
    template_id VARCHAR(50) NOT NULL REFERENCES document_templates(id) ON DELETE CASCADE,
    tags JSONB NOT NULL DEFAULT '{}'::jsonb,
    ...
);

-- AFTER (merges V12 attribute_definitions, V13 tags→attributes rename)
CREATE TABLE template_variants (
    id VARCHAR(50) NOT NULL CHECK (...),
    tenant_id VARCHAR(63) NOT NULL,
    template_id VARCHAR(50) NOT NULL,
    title VARCHAR(255),
    description TEXT,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, template_id) REFERENCES document_templates(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_template_variants_template ON template_variants(tenant_id, template_id);
```

Also add `variant_attribute_definitions` (merged from V12):
```sql
CREATE TABLE variant_attribute_definitions (
    id VARCHAR(50) NOT NULL,
    tenant_id VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    display_name VARCHAR(100) NOT NULL,
    allowed_values JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_modified TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id)
);
```

### V3: `template_versions`

```sql
-- BEFORE
CREATE TABLE template_versions (
    id INTEGER NOT NULL,
    variant_id VARCHAR(50) NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
    ...
    PRIMARY KEY (variant_id, id),
);

-- AFTER
CREATE TABLE template_versions (
    id INTEGER NOT NULL,
    tenant_id VARCHAR(63) NOT NULL,
    variant_id VARCHAR(50) NOT NULL,
    ...
    PRIMARY KEY (tenant_id, variant_id, id),
    FOREIGN KEY (tenant_id, variant_id) REFERENCES template_variants(tenant_id, id) ON DELETE CASCADE,
    CHECK (status IN ('draft', 'published', 'archived')),
    CHECK (id BETWEEN 1 AND 200)
);
CREATE INDEX idx_template_versions_variant ON template_versions(tenant_id, variant_id);
CREATE INDEX idx_template_versions_status ON template_versions(status);

CREATE UNIQUE INDEX idx_one_draft_per_variant
    ON template_versions (tenant_id, variant_id)
    WHERE status = 'draft';
```

### V3: `environment_activations`

```sql
-- BEFORE
CREATE TABLE environment_activations (
    environment_id VARCHAR(30) NOT NULL REFERENCES environments(id) ON DELETE CASCADE,
    variant_id VARCHAR(50) NOT NULL REFERENCES template_variants(id) ON DELETE CASCADE,
    version_id INTEGER NOT NULL,
    ...
    PRIMARY KEY (environment_id, variant_id),
    FOREIGN KEY (variant_id, version_id) REFERENCES template_versions(variant_id, id) ON DELETE CASCADE
);

-- AFTER
CREATE TABLE environment_activations (
    tenant_id VARCHAR(63) NOT NULL,
    environment_id VARCHAR(30) NOT NULL,
    variant_id VARCHAR(50) NOT NULL,
    version_id INTEGER NOT NULL,
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, environment_id, variant_id),
    FOREIGN KEY (tenant_id, environment_id) REFERENCES environments(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, variant_id) REFERENCES template_variants(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, variant_id, version_id) REFERENCES template_versions(tenant_id, variant_id, id) ON DELETE CASCADE
);
```

### V5: `documents`

Update FKs to use composite references:
```sql
FOREIGN KEY (tenant_id, template_id) REFERENCES document_templates(tenant_id, id) ON DELETE CASCADE,
FOREIGN KEY (tenant_id, variant_id) REFERENCES template_variants(tenant_id, id) ON DELETE CASCADE,
FOREIGN KEY (tenant_id, variant_id, version_id) REFERENCES template_versions(tenant_id, variant_id, id) ON DELETE CASCADE
```

### V5: `document_generation_requests`

Update FKs to use composite references:
```sql
FOREIGN KEY (tenant_id, template_id) REFERENCES document_templates(tenant_id, id) ON DELETE CASCADE,
FOREIGN KEY (tenant_id, variant_id) REFERENCES template_variants(tenant_id, id) ON DELETE CASCADE,
FOREIGN KEY (tenant_id, environment_id) REFERENCES environments(tenant_id, id) ON DELETE CASCADE,
CONSTRAINT fk_requests_variant_version
    FOREIGN KEY (tenant_id, variant_id, version_id)
    REFERENCES template_versions(tenant_id, variant_id, id)
    ON DELETE CASCADE
```

### V11: `load_test_runs`

Update FKs to use composite references:
```sql
FOREIGN KEY (tenant_id, template_id) REFERENCES document_templates(tenant_id, id) ON DELETE CASCADE,
FOREIGN KEY (tenant_id, variant_id) REFERENCES template_variants(tenant_id, id) ON DELETE CASCADE,
FOREIGN KEY (tenant_id, environment_id) REFERENCES environments(tenant_id, id) ON DELETE CASCADE,
FOREIGN KEY (tenant_id, variant_id, version_id) REFERENCES template_versions(tenant_id, variant_id, id) ON DELETE CASCADE
```

### Delete V12 and V13

Remove these files (content merged into V3):
- `V12__create_variant_attribute_definitions.sql`
- `V13__rename_tags_to_attributes.sql`

---

## Step 2: Domain Model Changes

### Add `tenantId` to `TemplateVariant`

**Modify**: `modules/epistola-core/.../templates/model/TemplateVariant.kt`

```kotlin
data class TemplateVariant(
    val id: VariantId,
    val tenantId: TenantId,  // NEW
    val templateId: TemplateId,
    val title: String?,
    val description: String?,
    @Json val attributes: Map<String, String> = emptyMap(),
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)
```

`VariantSummary`, `TemplateVersion`, `VersionSummary` — **no changes needed**. These are always queried in a context where `tenantId` is already known. The DB has `tenant_id` for FK integrity, but the application doesn't need to carry it in these models.

---

## Step 3: Query Simplification

Many queries currently JOIN to `document_templates` solely for tenant isolation. With `tenant_id` directly on `template_variants`, these JOINs can be removed.

### Pattern: Before → After

```sql
-- BEFORE (JOIN for tenant check)
SELECT tv.id, tv.template_id, tv.attributes, tv.created_at, tv.last_modified
FROM template_variants tv
JOIN document_templates dt ON tv.template_id = dt.id
WHERE tv.template_id = :templateId
  AND dt.tenant_id = :tenantId

-- AFTER (direct tenant check)
SELECT tv.id, tv.tenant_id, tv.template_id, tv.attributes, tv.created_at, tv.last_modified
FROM template_variants tv
WHERE tv.template_id = :templateId
  AND tv.tenant_id = :tenantId
```

### Files to update (queries)

| File | Change |
|------|--------|
| `queries/variants/ListVariants.kt` | Remove JOIN to document_templates, add `tv.tenant_id` to SELECT, WHERE on `tv.tenant_id` |
| `queries/variants/GetVariant.kt` | Remove JOIN to document_templates, add `tv.tenant_id` to SELECT, WHERE on `tv.tenant_id` |
| `queries/variants/GetVariant.kt` (GetVariantSummaries) | Add `tv.tenant_id` to WHERE |
| `queries/versions/GetVersion.kt` | Update JOINs to include tenant_id in FK conditions |
| `queries/versions/GetDraft.kt` | Update JOINs to include tenant_id in FK conditions |
| `queries/versions/GetDraftForPreview.kt` | Update JOINs to include tenant_id in FK conditions |
| `queries/versions/ListVersions.kt` | Update JOINs to include tenant_id in FK conditions |
| `queries/activations/GetActiveVersion.kt` | Update JOINs for composite FKs |
| `queries/activations/ListActivations.kt` | Update JOINs for composite FKs |
| `queries/GetDocumentTemplate.kt` | Update WHERE to `WHERE dt.tenant_id = :tenantId AND dt.id = :id` |
| `queries/ListDocumentTemplates.kt` | Verify tenant_id in WHERE |
| `queries/ListTemplateSummaries.kt` | Update JOINs for composite FKs |
| `queries/GetEditorContext.kt` | Update JOINs for composite FKs |
| `environments/queries/GetEnvironment.kt` | Update WHERE to use composite PK |
| `environments/queries/ListEnvironments.kt` | Verify tenant_id in WHERE |
| `documents/queries/*.kt` | Update JOINs for composite FKs where applicable |

### Files to update (commands)

| File | Change |
|------|--------|
| `commands/CreateDocumentTemplate.kt` | No SQL change needed (already INSERTs tenant_id) |
| `commands/UpdateDocumentTemplate.kt` | Update WHERE to `WHERE tenant_id = :tenantId AND id = :id` |
| `commands/DeleteDocumentTemplate.kt` | Update WHERE to `WHERE tenant_id = :tenantId AND id = :id` |
| `commands/UpdateDataExample.kt` | Update WHERE for composite PK |
| `commands/DeleteDataExample.kt` | Update WHERE for composite PK |
| `commands/variants/CreateVariant.kt` | Add `tenant_id` to INSERT, update ownership check |
| `commands/variants/UpdateVariant.kt` | Simplify ownership check (no JOIN needed), add tenant_id to WHERE |
| `commands/variants/DeleteVariant.kt` | Simplify ownership check (no JOIN needed), add tenant_id to WHERE |
| `commands/versions/UpdateDraft.kt` | Update JOINs for composite FKs |
| `commands/versions/PublishVersion.kt` | Update JOINs for composite FKs |
| `commands/versions/ArchiveVersion.kt` | Update JOINs for composite FKs |
| `commands/activations/SetActivation.kt` | Add tenant_id to INSERT/UPSERT, update FKs |
| `commands/activations/RemoveActivation.kt` | Update WHERE for composite FKs |
| `environments/commands/CreateEnvironment.kt` | Verify PK usage |
| `environments/commands/UpdateEnvironment.kt` | Update WHERE for composite PK |
| `environments/commands/DeleteEnvironment.kt` | Update WHERE and JOINs |
| `documents/commands/GenerateDocument.kt` | Update FK verification queries |
| `documents/commands/GenerateDocumentBatch.kt` | Update FK verification queries |

---

## Step 4: Service Updates

**Modify**: `templates/services/VariantResolver.kt`
- No structural change needed — it calls `ListVariants(tenantId, templateId)` which already passes both IDs

---

## Step 5: REST API

### DTO Mappers
**Modify**: `rest-api/.../shared/DtoMappers.kt`
- `TemplateVariant.toDto()`: no change needed (tenantId not exposed in DTO)

### Controllers
**Modify**: `rest-api/.../v1/EpistolaTemplateApi.kt`
- Already passes `tenantId` to all commands/queries — review for correctness but likely no changes

**Modify**: `rest-api/.../v1/EpistolaTenantApi.kt`
- Review environment operations

---

## Step 6: UI Handlers

All handlers already extract `tenantId` from the path. Changes are minimal — ensure commands/queries receive it where newly needed.

| File | Change |
|------|--------|
| `handlers/DocumentTemplateHandler.kt` | Review command/query calls |
| `handlers/VariantRouteHandler.kt` | Pass tenantId to variant commands |
| `handlers/VersionRouteHandler.kt` | Pass tenantId to version commands |
| `handlers/EnvironmentHandler.kt` | Review command/query calls |
| `handlers/TemplatePreviewHandler.kt` | Review ID passing |

---

## Step 7: Document Generation + Load Tests

These tables (`documents`, `document_generation_requests`, `load_test_runs`) already have `tenant_id` columns. The FK references just need updating to point at composite PKs. The Kotlin code already passes `tenantId` in commands.

| File | Change |
|------|--------|
| `documents/commands/GenerateDocument.kt` | Update FK verification queries to use composite keys |
| `documents/commands/GenerateDocumentBatch.kt` | Update FK verification queries |
| `documents/batch/DocumentGenerationExecutor.kt` | Review ID usage |
| `loadtest/commands/StartLoadTest.kt` | Review FK verification |
| `loadtest/batch/LoadTestExecutor.kt` | Review ID usage |

---

## Step 8: Demo Data

**Modify**: `apps/epistola/.../demo/DemoLoader.kt`
- Likely no changes — commands already accept tenantId. But verify variant/version creation.

---

## Step 9: Tests

### Test infrastructure
- Update `CoreIntegrationTestBase` / test fixtures if they create templates/variants directly
- Update `TestIdHelpers` if slug uniqueness assumptions change

### Tenant isolation test
- `TenantIsolationTest.kt`: This is the **critical** test — verify that two tenants can now create templates with the same slug (e.g., both create "invoice")

### All existing tests
- Should continue to pass since they already use unique IDs per test. Run full suite and fix any failures.

---

## Step 10: Documentation + Cleanup

- **Modify**: `docs/attributes.md` — update schema section
- **Modify**: `CHANGELOG.md` — add entry
- **Delete**: `V12__create_variant_attribute_definitions.sql`
- **Delete**: `V13__rename_tags_to_attributes.sql`

---

## Verification

1. `./gradlew ktlintFormat && ./gradlew ktlintCheck`
2. `./gradlew test` — all tests pass
3. Manual: verify two tenants can create templates with the same slug
4. `./gradlew build` — full build succeeds
