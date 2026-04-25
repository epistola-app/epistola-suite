# Contract Schema Versioning

## Problem

Template contracts (the data schema, data model, and data examples that define what data a template accepts) were previously unversioned — mutable JSONB columns on `document_templates`. All variant versions, including published ones, implicitly shared the latest contract. A schema change could silently invalidate published versions active in production.

## Solution

Versioned contracts with a draft/published lifecycle, explicit FK from template versions to contract versions, and automatic compatibility checking on publish.

## Invariants

1. **Every template has at least one contract version.** `CreateDocumentTemplate` auto-creates contract v1 (draft, empty).
2. **At most one draft contract per template.** Enforced by unique partial index on `(tenant_key, catalog_key, template_key) WHERE status = 'draft'`.
3. **At most one draft template version per variant.** Enforced by existing unique partial index.
4. **Every template version has a `contract_version` FK.** Set on creation by all code paths: `CreateDocumentTemplate`, `CreateVersion`, `CreateVariant`, `UpdateDraft`, `ImportTemplates`.
5. **A draft only exists when there are unpublished changes.** On-demand pattern — publishing does NOT auto-create the next draft. The user must save/edit to create one.
6. **Compatible contract changes auto-publish transparently.** When publishing a template version, if the contract is a draft and backwards-compatible (or the first version), it is auto-published.
7. **Breaking contract changes block template version publish.** The user must explicitly publish the contract (via `PublishContractVersion`) to acknowledge and accept breaking changes.
8. **Contract versions are never deleted individually.** They are immutable history. Only template deletion cascades to contract deletion.

## Lifecycle

### On-demand draft pattern

All versioned entities (template versions, contract versions, stencil versions) follow the same pattern:

```
Created → v1 DRAFT
Edit → update draft in place
Publish → draft becomes published, NO auto-created next draft
Edit again → new draft created on save (copy of published)
```

### Template lifecycle flows

#### Create template

```
CreateDocumentTemplate:
  → document_templates row
  → contract_versions: v1 DRAFT (empty, no schema)
  → template_variants: default variant
  → template_versions: v1 DRAFT, contract_version = 1
```

#### Create additional variant

```
CreateVariant:
  → template_variants: new variant
  → template_versions: v1 DRAFT, contract_version = latest (published preferred)
```

#### Edit layout

```
UpdateDraft (draft exists):
  → updates template_model on existing draft
  → contract_version unchanged

UpdateDraft (no draft exists, e.g. after publish):
  → creates new version N+1 DRAFT
  → resolves contract_version (draft preferred, then published)
```

#### Publish to environment

```
PublishToEnvironment:
  1. If contract_version points to PUBLISHED contract:
     → just publish the template version (no contract interaction)

  2. If contract_version points to DRAFT contract:
     → compare draft vs latest published contract
     → no previous published: auto-publish (first time, always compatible)
     → compatible: auto-publish + upgrade all versions from old→new contract
     → breaking: BLOCK with error listing breaking changes

  3. Freeze template version (status=published, snapshot theme+rendering)
  4. Upsert environment activation
  5. NO auto-draft created
```

#### Archive version

```
ArchiveVersion:
  → status=archived, contract_version unchanged
  → blocked if version is active in any environment
```

### Contract lifecycle flows

#### Start editing contract

```
CreateContractVersion:
  → if draft exists: return it (idempotent)
  → if published exists: copy schema/dataModel/examples → new DRAFT
  → if only initial draft exists: return it
  → link all DRAFT template versions to new contract version
```

#### Save contract changes

```
UpdateContractVersion:
  → requires existing draft (returns null if none)
  → updates schema/dataModel/examples on the draft
  → validates examples against schema
```

#### Publish contract explicitly

```
PublishContractVersion:
  → validates schema + examples
  → checks compatibility vs previous published
  → publishes draft (status=published)
  → auto-upgrade template versions:
    - compatible: ALL versions (draft+published+archived) from N-1→N
    - breaking: only DRAFT versions from N-1→N
  → NO auto-draft created
```

#### Edit/delete individual example

```
UpdateDataExample / DeleteDataExample:
  → operates on DRAFT contract (FOR UPDATE lock)
  → returns null if no draft exists
```

### Import/export flows

#### Export

```
ExportCatalogZip:
  → joins contract_versions (latest published preferred)
  → includes dataModel + dataExamples in template resource
```

#### Import

```
ImportTemplates:
  → upserts document_templates
  → if contract data provided: creates PUBLISHED contract version
  → links ALL template versions to new contract
  → creates published template versions per variant
```

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
| Add enum constraint                     | **No**      |
| Remove enum values                      | **No**      |
| Add enum values                         | Yes         |
| Add pattern constraint                  | **No**      |
| Change field description                | Yes         |
| Add nested optional fields to object    | Yes         |
| Remove nested fields from object        | **No**      |

## Auto-Upgrade Logic

### Compatible publish

All template versions on the previous contract version are upgraded:

```sql
UPDATE template_versions
SET contract_version = :newVersion
WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
  AND template_key = :templateKey AND contract_version = :previousVersion;
```

Safe because backwards compatibility guarantees no breakage.

### Breaking publish

Only draft template versions are upgraded:

```sql
UPDATE template_versions
SET contract_version = :newVersion
WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
  AND template_key = :templateKey AND contract_version = :previousVersion
  AND status = 'draft';
```

Published/archived versions stay on the old contract version — "stranded" but functional.

### Cascading

Only upgrades from the immediately previous version (N-1 → N). Versions on older contracts are not auto-upgraded.

## Data Model

### `contract_versions` table

```sql
CREATE TABLE contract_versions (
    id INTEGER NOT NULL,                    -- Sequential 1-200
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL DEFAULT 'default',
    template_key TEMPLATE_KEY NOT NULL,
    schema JSONB,                           -- JSON Schema for strict validation
    data_model JSONB,                       -- JSON Schema for visual editor
    data_examples JSONB DEFAULT '[]',       -- Named sample data sets
    status VARCHAR(20) NOT NULL DEFAULT 'draft',  -- draft | published
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    created_by UUID REFERENCES users(id),
    PRIMARY KEY (tenant_key, catalog_key, template_key, id),
    FOREIGN KEY (...) REFERENCES document_templates ON DELETE CASCADE
);

-- At most one draft per template
CREATE UNIQUE INDEX idx_one_draft_contract_per_template
    ON contract_versions (...) WHERE status = 'draft';

-- Fast published contract lookups
CREATE INDEX idx_contract_versions_published
    ON contract_versions (..., id DESC) WHERE status = 'published';
```

### `template_versions.contract_version` FK

```sql
ALTER TABLE template_versions ADD COLUMN contract_version INTEGER;
ALTER TABLE template_versions ADD CONSTRAINT fk_template_versions_contract_version
    FOREIGN KEY (..., contract_version) REFERENCES contract_versions(..., id);
```

No `ON DELETE CASCADE` — contract versions cannot be deleted individually (only via template CASCADE).

## Prior Art

### Already on `main` (PR #330)

Breaking change detection, real-time banner, atomic save, example sync, single-page layout, migration utils, property name validation, `DataContractState`.

### `feature/280-schema-change-refactor` (unmerged)

Draft/published via columns (different approach), `DataContractStore` rewrite, `SaveOrchestrator`, schema fix screen, recent usage compatibility (separate feature), enhanced validation. The store rewrite and fix screen are valuable follow-up improvements.

### Scope boundary

**Recent usage compatibility** is a separate feature. It validates schemas against real production data and complements the structural compatibility checking.
