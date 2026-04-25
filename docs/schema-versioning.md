# Contract Schema Versioning

## Problem

Template contracts (the data schema, data model, and data examples that define what data a template accepts) are currently **unversioned**. They live as mutable JSONB columns on the `document_templates` table. All variant versions — including published, frozen ones — implicitly share the latest contract.

This creates a fundamental safety problem: published template versions are immutable (their visual content can't be re-edited), yet the contract they rely on can change out from under them. A breaking schema change could silently invalidate published versions that are active in production environments.

## Goals

1. **Versioned contracts** with a draft/published lifecycle, similar to template versions
2. **Explicit association** between each template variant version and a specific contract version
3. **Backwards-compatible auto-upgrade**: when a new compatible contract is published, automatically upgrade all template versions referencing the previous contract
4. **Breaking change protection**: never silently break published template versions
5. **Shared compatibility tooling** in both frontend and backend to determine schema-to-schema and data-to-schema compatibility
6. **UI visibility** into which contract version each variant/version uses

## Data Model

### New table: `contract_versions`

Stores versioned contracts per template. Sequential integer IDs (1–200), matching the `template_versions` convention.

```sql
CREATE TABLE contract_versions (
    id INTEGER NOT NULL,
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL DEFAULT 'default',
    template_key TEMPLATE_KEY NOT NULL,
    schema JSONB,
    data_model JSONB,
    data_examples JSONB DEFAULT '[]'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP WITH TIME ZONE,
    created_by UUID REFERENCES users(id),
    PRIMARY KEY (tenant_key, catalog_key, template_key, id),
    FOREIGN KEY (tenant_key, catalog_key, template_key)
        REFERENCES document_templates(tenant_key, catalog_key, id) ON DELETE CASCADE,
    CHECK (status IN ('draft', 'published')),
    CHECK (id BETWEEN 1 AND 200)
);

-- At most one draft contract per template
CREATE UNIQUE INDEX idx_one_draft_contract_per_template
    ON contract_versions (tenant_key, catalog_key, template_key)
    WHERE status = 'draft';
```

Key decisions:

- **Scoped to template, not variant.** The contract defines the input structure for all variants. Different variants (language, brand) share the same contract.
- **No archived status.** Contract versions are either draft or published. They never need archiving because they are referenced by FK from template versions, providing their history.
- **`data_examples` live here.** Examples are tightly coupled to the schema they were written against. When a contract is published, its examples are frozen with it. The next auto-created draft copies them forward.

### Changes to `template_versions`

Add a nullable FK to the contract version:

```sql
ALTER TABLE template_versions
    ADD COLUMN contract_version INTEGER;

ALTER TABLE template_versions
    ADD CONSTRAINT fk_template_versions_contract_version
    FOREIGN KEY (tenant_key, catalog_key, template_key, contract_version)
    REFERENCES contract_versions(tenant_key, catalog_key, template_key, id);
```

Nullable to handle templates with no contract yet (schema is NULL).

### Changes to `document_templates`

Remove contract-related columns. Since the project is not in production, this is a destructive migration:

```sql
ALTER TABLE document_templates
    DROP COLUMN schema,
    DROP COLUMN data_model,
    DROP COLUMN data_examples;
```

### Domain types

```kotlin
data class ContractVersion(
    val id: VersionKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val templateKey: TemplateKey,
    @Json val schema: ObjectNode?,
    @Json val dataModel: ObjectNode?,
    @Json val dataExamples: DataExamples,
    val status: ContractVersionStatus,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
)

enum class ContractVersionStatus {
    DRAFT,
    PUBLISHED,
}
```

New composite ID in the `EntityId` hierarchy:

```kotlin
class ContractVersionId(
    key: VersionKey,
    val templateId: TemplateId,
) : EntityId<VersionKey, Int, TemplateId>(key, templateId) {
    override val type = "contract-version"
    val tenantKey get() = templateId.tenantKey
    val catalogKey get() = templateId.catalogKey
}
```

## Contract Version Lifecycle

```
(no contract) ──► DRAFT ──► PUBLISHED
                    ▲            │
                    │            │ auto-create next draft
                    └────────────┘
```

### Creating the first contract version

When a template has no contract versions and the user starts defining a schema, create contract version 1 in DRAFT status. Assign `contract_version = 1` to all existing template version drafts.

### Editing a draft

The draft contract is mutable. Changes to `schema`, `data_model`, and `data_examples` update the draft in place. This matches how template version drafts work today.

### Publishing a contract version

Publishing freezes the draft. The flow:

1. Validate the schema is valid JSON Schema 2020-12
2. Validate all data examples against the schema
3. Validate property names are valid identifiers
4. If a previous published version exists, run backwards-compatibility analysis
5. Set `status = 'published'`, `published_at = NOW()`
6. Auto-upgrade template versions (see [Auto-Upgrade Logic](#auto-upgrade-logic))
7. Auto-create next draft: copy the published contract into a new draft (contract version N+1)

Contract publishing is an **explicit user action**, separate from template version publishing. The UI has a dedicated "Publish Contract" button.

### Interaction with template version publishing

When publishing a template version to an environment (`PublishToEnvironment`):

- If the template version's `contract_version` is NULL: allow (no contract required)
- If it references a **draft** contract version: **block the publish** — the contract must be published first
- If it references a **published** contract version: proceed normally

## Backwards Compatibility Rules

A new contract version is **backwards compatible** when all data valid under the old schema remains valid under the new schema.

| Change                                  | Compatible? |
| --------------------------------------- | ----------- |
| Add optional field                      | Yes         |
| Add optional field with default         | Yes         |
| Remove a field                          | **No**      |
| Rename a field                          | **No**      |
| Change field type                       | **No**      |
| Make optional field required            | **No**      |
| Add new required field                  | **No**      |
| Widen type constraints (remove min/max) | Yes         |
| Narrow type constraints (add min/max)   | **No**      |
| Change field description                | Yes         |
| Add nested optional fields to object    | Yes         |
| Remove nested fields from object        | **No**      |

### Backend: `SchemaCompatibilityChecker`

New component in `modules/epistola-core/.../templates/validation/`:

```kotlin
@Component
class SchemaCompatibilityChecker {
    fun checkCompatibility(
        oldSchema: ObjectNode?,
        newSchema: ObjectNode?,
    ): CompatibilityResult
}

data class CompatibilityResult(
    val compatible: Boolean,
    val breakingChanges: List<BreakingChange>,
)

data class BreakingChange(
    val type: BreakingChangeType,
    val path: String,
    val description: String,
)

enum class BreakingChangeType {
    FIELD_REMOVED,
    FIELD_RENAMED,
    TYPE_CHANGED,
    REQUIRED_ADDED,
    MADE_REQUIRED,
    CONSTRAINT_NARROWED,
}
```

Operates on raw JSON Schema `ObjectNode` (not visual schema fields) so it works with schemas that are not representable by the visual editor.

### Frontend

The existing `schemaBreakingChanges.ts` already detects breaking changes on the visual schema. It uses stable field IDs to distinguish renames from add/delete pairs. For contract versioning, the frontend needs to:

1. Compare draft contract schema vs. last published contract schema (not just committed vs. dirty)
2. Show a live breaking-changes indicator in the data contract tab
3. The existing `detectBreakingChanges()` function is reused with a new `previousPublishedSchema` property on `DataContractState`

## Auto-Upgrade Logic

### Backwards-compatible publish

When a new contract version N is published and it IS backwards compatible with version N-1, upgrade **all** template versions (draft, published, and archived) from N-1 to N:

```sql
UPDATE template_versions
SET contract_version = :newVersion
WHERE tenant_key = :tenantKey
  AND catalog_key = :catalogKey
  AND template_key = :templateKey
  AND contract_version = :previousVersion;
```

This is safe because backwards compatibility guarantees all data valid under N-1 is also valid under N. Published template versions' `template_model` does not change.

### Breaking publish

When the new contract is NOT backwards compatible, upgrade **only draft** template versions:

```sql
UPDATE template_versions
SET contract_version = :newVersion
WHERE tenant_key = :tenantKey
  AND catalog_key = :catalogKey
  AND template_key = :templateKey
  AND contract_version = :previousVersion
  AND status = 'draft';
```

Published and archived versions remain on the old contract version. They are "stranded" but still functional — they reference a published contract that won't change.

### Cascading

Only upgrade versions on the **immediately previous** contract version (N-1 → N). Versions on older contracts (N-2, N-3, etc.) are NOT auto-upgraded. This avoids unexpected leaps across multiple contract versions.

## Edge Cases

### Template with no contract (schema is NULL)

- `contract_versions` has zero rows for this template
- All `template_versions.contract_version` are NULL
- When the user first defines a schema, create contract version 1 as draft
- Assign `contract_version = 1` to all existing template version drafts

### Creating a new variant

When a new variant is created and its first draft version is auto-created:

- If a published contract exists: set `contract_version` to the latest published
- If only a draft contract exists: set `contract_version` to the draft
- If no contract exists: set `contract_version` to NULL

### Editing the contract while variant drafts exist

Draft template versions point to a specific contract version. When the user edits the **draft contract**, draft template versions pointing to that draft contract version automatically see the new schema (the same contract version row is being mutated). This matches current behavior.

### Data examples per contract version

Each contract version has its own set of examples. When a new draft is auto-created after publishing, it copies the examples from the just-published version. This means:

- Published contract versions have immutable examples (frozen)
- The draft contract version's examples are editable
- The template editor's preview uses examples from the contract version referenced by the current template draft

### Generation and preview

`GenerationService` and `PreviewDocument` currently read `template.dataModel`. They need to:

1. Read the template version's `contract_version`
2. Load the corresponding contract version
3. Validate against that contract version's schema/data_model

For published versions activated in an environment, this ensures validation against the contract they were published with, not the latest.

## UI Changes

### Data contract tab

- Show the **current contract version number** and status badge (e.g., "v3 draft", "v2 published")
- Schema editor and examples editor operate on the **draft contract version**
- If no draft exists (only published), show a "Create New Draft" button
- **Breaking changes banner**: comparing draft vs. last published
  - Green: "All changes are backwards compatible"
  - Red: "N breaking changes detected" with expandable list
- **"Publish Contract" button** with confirmation dialog showing:
  - Compatibility analysis result
  - List of affected template versions (what will be auto-upgraded)
  - Warning about stranded versions (if breaking)

### Template editor

- Show a badge indicating which contract version is in use (e.g., "Contract v3")
- If a newer contract version exists, show a notification: "A newer contract version is available"

### Contract version history

New panel on the data contract tab listing all contract versions:

- Version number, status, created/published dates
- Whether it was a breaking change from the previous version
- Number of template versions referencing it

### Variant version listings

Add a "Contract" column to the version history dialog showing which contract version each template version uses.

## API and Command Changes

### New commands

| Command                  | Description                                                      |
| ------------------------ | ---------------------------------------------------------------- |
| `CreateContractVersion`  | Creates first draft contract version for a template              |
| `UpdateContractVersion`  | Updates draft contract's schema, data_model, or data_examples    |
| `PublishContractVersion` | Publishes draft, runs compatibility check, triggers auto-upgrade |

### New queries

| Query                               | Description                                                   |
| ----------------------------------- | ------------------------------------------------------------- |
| `GetContractVersion`                | Gets a specific contract version by template + version number |
| `GetDraftContractVersion`           | Gets the current draft contract version for a template        |
| `GetLatestPublishedContractVersion` | Gets the latest published contract version                    |
| `ListContractVersions`              | Lists all contract versions for a template                    |

### Modified commands

| Command                  | Change                                                                         |
| ------------------------ | ------------------------------------------------------------------------------ |
| `UpdateDocumentTemplate` | Remove `dataModel`, `dataExamples` handling — moves to `UpdateContractVersion` |
| `CreateVersion`          | Set `contract_version` on new drafts (latest published or draft)               |
| `PublishToEnvironment`   | Guard: reject if referenced contract version is still a draft                  |

### Modified queries

| Query                 | Change                                                                  |
| --------------------- | ----------------------------------------------------------------------- |
| `GetEditorContext`    | Join through `contract_versions` to get schema/data_model/data_examples |
| `GetDocumentTemplate` | Remove schema, data_model, data_examples from result                    |

### New UI handler routes

```
GET  /{catalogId}/{id}/contract-versions                        — list all
POST /{catalogId}/{id}/contract-versions                        — create draft
PATCH /{catalogId}/{id}/contract-versions/draft                 — update draft
POST /{catalogId}/{id}/contract-versions/draft/publish          — publish
GET  /{catalogId}/{id}/contract-versions/{versionId}            — get specific
```

Data example routes move under contract versions:

```
PATCH  /{catalogId}/{id}/contract-versions/draft/data-examples/{exampleId}
DELETE /{catalogId}/{id}/contract-versions/draft/data-examples/{exampleId}
```

### REST API changes

New endpoints under `/api/tenants/{tenantId}/catalogs/{catalogId}/templates/{templateId}/`:

```
GET    /contract-versions                     — list contract versions
GET    /contract-versions/draft               — get draft contract
PUT    /contract-versions/draft               — upsert draft contract
POST   /contract-versions/draft/publish       — publish contract
GET    /contract-versions/{versionId}         — get specific version
POST   /contract-versions/draft/validate      — validate schema against examples
```

### Catalog import/export

`ExportCatalogZip` and `ImportCatalogZip` need to serialize/deserialize contract versions. The catalog exchange format should include:

- Published contract versions for each template
- Each template version's `contract_version` reference

## Prior Art

### Already on `main`

PR #330 (`fix: editor, data contract, and generation improvements`) landed significant contract editor work that this design builds on:

- **Breaking change detection** — `schemaBreakingChanges.ts` detects field removal, renames, type changes, new required fields, and optional-to-required transitions. Uses stable field IDs to distinguish renames from add/delete pairs.
- **Breaking changes banner** — Live real-time banner during editing, plus a confirmation dialog before save.
- **Atomic schema + examples save** — When both are dirty (e.g. field rename), examples are included in the schema save request so the backend validates them together.
- **Example sync on schema changes** — Deleting a schema field strips orphaned keys from examples; renaming a field renames the key in examples.
- **Single-page layout** — Schema and examples on one scrollable page, JSON view as collapsible panel, save/undo/redo in toolbar.
- **Schema migration utilities** — `schemaMigration.ts` for detecting and suggesting data transformations.
- **Property name validation** — Backend `JsonSchemaValidator` validates property names on save and catalog import.
- **`DataContractState`** — EventTarget-based state management with dirty tracking for schema and examples.

### `feature/280-schema-change-refactor` (unmerged)

This branch tackled a subset of contract versioning with a different backend approach. It has NOT been merged to `main`.

**Draft/published contract via columns** — Added `draft_data_model` and `draft_data_examples` columns to `document_templates` (V23 migration). Binary draft/published split: edits go to draft columns, publishing promotes draft to published and clears the drafts. No version history, no explicit FK from template versions to a contract version.

**Frontend store rewrite** — Replaced `DataContractState` (EventTarget) with `DataContractStore` (Redux-like reducer with discriminated union commands). Added `SaveOrchestrator` for pure, testable save decision logic.

**Schema fix screen** — Interactive overlay for fixing examples that don't match a new schema: inline editing, bulk "remove unknown fields", atomic save.

**Recent usage compatibility** — `TemplateRecentUsageCompatibilityService` validates new schemas against actual production request data. `PublishRiskDialog` with time-period filters. **This is out of scope for this design and will be a separate feature.**

**Enhanced validation** — Better error messages in `JsonSchemaValidator`, type-aware reporting, JSON Pointer path handling, `nestedValue.ts` for path-based JSON manipulation.

### What neither has (and this design adds)

| Gap                                                                     | This design's solution                                     |
| ----------------------------------------------------------------------- | ---------------------------------------------------------- |
| No contract version history                                             | `contract_versions` table with sequential IDs              |
| No link between template version and contract                           | `template_versions.contract_version` FK                    |
| No auto-upgrade on compatible publish                                   | Bulk UPDATE of template version FKs                        |
| No publish guard (template version can publish with any contract state) | Block `PublishToEnvironment` if contract is draft          |
| No contract version UI/matrix                                           | Version history panel, contract column in variant listings |
| No catalog import/export support                                        | Contract versions included in catalog exchange format      |

### Implementation order

Contract versioning lands first — it changes the data model, commands, queries, and frontend-backend interface. The 280 branch work (store rewrite, fix screen, enhanced validation) can be done as follow-up improvements on top of the new model, avoiding rework.

### Scope boundary

**Recent usage compatibility** (`TemplateRecentUsageCompatibilityService`, `PublishRiskDialog`) is a separate feature that validates schemas against real production data. It complements structural schema-to-schema comparison but is not part of the contract versioning design. It can be layered on later as an additional publish-time check.

## Implementation Plan

### Phase 1: Database and Domain Model

1. Create Flyway migration `V23__contract_versions.sql`:
   - Create `contract_versions` table
   - Add `contract_version` column to `template_versions`
   - Drop `schema`, `data_model`, `data_examples` from `document_templates`
2. Add `ContractVersion` entity, `ContractVersionStatus` enum
3. Add `ContractVersionId` to `EntityId` hierarchy
4. Update `DocumentTemplate` — remove schema/dataModel/dataExamples fields
5. Update `TemplateVersion` — add `contractVersion` field

### Phase 2: Backend Commands, Queries, and Compatibility Checker

6. Implement `CreateContractVersion` command
7. Implement `UpdateContractVersion` command (schema, data_model, data_examples)
8. Implement `PublishContractVersion` command (validation, compatibility check, auto-upgrade, auto-create draft)
9. Implement `SchemaCompatibilityChecker`
10. Implement `GetContractVersion`, `GetDraftContractVersion`, `ListContractVersions` queries
11. Update `CreateVersion` to set `contract_version` on new drafts
12. Update `PublishToEnvironment` to guard against draft contracts
13. Update `GetEditorContext` to join through `contract_versions`
14. Update `UpdateDocumentTemplate` to remove contract handling
15. Update generation/preview to read contract from contract version FK

### Phase 3: UI Handlers

16. Create `ContractVersionHandler` with routes for list, create, update, publish
17. Move data example routes to contract version scope
18. Update existing data contract tab handler to pass contract version info

### Phase 4: Frontend Editor

19. Update `DataContractState` with contract version awareness (version info, previous published schema)
20. Update `EpistolaDataContractEditor` — contract version badge, publish button
21. Add breaking changes banner (draft vs. last published contract)
22. Update `SaveCallbacks` to route saves to contract version endpoints

### Phase 5: Matrix and History UI

23. Add contract version column to variant version history dialog
24. Add contract version history panel to data contract tab
25. Show contract version badge in template editor

### Phase 6: Catalog Import/Export

26. Update `ExportCatalogZip` to include contract versions
27. Update `ImportCatalogZip` to create contract versions during import
28. Update demo catalog loader

### Phase 7: Testing

29. Unit tests for `SchemaCompatibilityChecker` (all compatibility rules)
30. Integration tests for contract version commands (create, update, publish, auto-upgrade)
31. Integration tests for publish guards (reject draft contract on template publish)
32. Update existing template tests to work with contract versions
33. Frontend tests for contract version state management
