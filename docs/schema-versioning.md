# Contract Schema Versioning

## Overview

Template data contracts (the JSON Schema defining what data a template accepts) are versioned separately from the template's visual content. Each template has a history of contract versions with a draft/published lifecycle, and each template version is linked to a specific contract version.

## Package Structure

```
templates/
  contracts/                          # Contract versioning domain
    commands/                         # State changes
      CreateContractVersion.kt        # Create draft to start editing
      UpdateContractVersion.kt        # Update draft schema/examples
      PublishContractVersion.kt       # Publish draft with compatibility checks
    queries/                          # Read operations
      CheckSchemaCompatibility.kt     # Schema diff against published
      CheckContractPublishImpact.kt   # Full publish impact analysis
      CheckTemplateVersionCompatibility.kt  # Per-version field usage check
      GetContractVersion.kt
      GetDraftContractVersion.kt
      GetLatestContractVersion.kt
      GetLatestPublishedContractVersion.kt
      ListContractVersions.kt
    model/
      ContractVersion.kt             # Entity + status enum + summary
    SchemaCompatibilityChecker.kt    # Schema structural diff utility
    SchemaPathNavigator.kt           # JSON Schema tree navigation utility
  analysis/
    TemplatePathExtractor.kt         # Extracts variable paths from expressions
    TemplateCompatibilityResult.kt   # Result types for compatibility checks
```

## Invariants

1. **Every template has at least one contract version.** `CreateDocumentTemplate` auto-creates contract v1 (draft, empty).
2. **At most one draft contract per template.** Enforced by unique partial index.
3. **Every template version has a `contract_version` FK.** Set on creation by all code paths.
4. **A draft only exists when there are unpublished changes.** On-demand pattern — publishing does NOT auto-create the next draft.
5. **Compatible contract changes auto-publish transparently.** `PublishToEnvironment` auto-publishes the contract if it's backwards-compatible or the first version.
6. **Breaking contract changes require explicit publish.** The user must call `PublishContractVersion` with confirmation to accept breaking changes.
7. **Incompatibility is derived, not stored.** A template version is incompatible when its `contract_version` doesn't match the latest published contract AND its `referenced_paths` include affected fields.
8. **One "current" published contract.** The latest published contract is what downstream systems use. Template versions on older contracts are flagged in the UI.

## Lifecycle

### On-demand draft pattern

All versioned entities (template versions, contract versions, stencil versions) follow this pattern:

```
Created → v1 DRAFT
Edit → update draft in place
Publish → draft becomes published, NO auto-created next draft
Edit again → explicit CreateContractVersion copies from published → new draft
```

### Template version publish flow

```
PublishToEnvironment:
  1. If contract_version points to PUBLISHED contract:
     → just publish the template version

  2. If contract_version points to DRAFT contract:
     → check compatibility against latest published contract
     → compatible (or first version): auto-publish contract, publish template version
     → breaking: BLOCK — user must publish contract explicitly first
```

### Contract publish flow

```
PublishContractVersion(confirmed=false):
  → preview mode: returns breaking changes + affected template versions
  → does not publish

PublishContractVersion(confirmed=true):
  → validates schema + examples
  → publishes the draft
  → auto-upgrades compatible template versions to new contract
  → leaves truly incompatible versions on old contract
```

### Breaking change workflow (zero downtime)

```
1. User edits contract (CreateContractVersion → draft created from published)
2. User edits template layouts to match new contract
3. User publishes contract with confirmation:
   → shows preview: which versions are incompatible, which are fine
   → compatible versions upgraded, incompatible stay on old contract
4. User deploys new template versions to replace incompatible ones
5. Old versions can be archived when no longer active
```

## Compatibility Checking

### Three levels

1. **Schema structural diff** (`SchemaCompatibilityChecker`): compares two schemas for field removal, type changes, required changes, constraint narrowing. Used for the breaking changes banner.

2. **Template field usage** (`TemplatePathExtractor`): extracts which data paths a template's expressions actually reference. Stored as `referenced_paths` on `template_versions`.

3. **Per-version compatibility** (`CheckTemplateVersionCompatibility`): cross-references a template version's `referenced_paths` against the new schema. Reports `FIELD_REMOVED` and `TYPE_CHANGED` only for fields the template actually uses.

### Breaking change rules (schema level)

| Change                                      | Breaking? |
| ------------------------------------------- | --------- |
| Add optional field                          | No        |
| Remove field                                | **Yes**   |
| Change field type                           | **Yes**   |
| Make optional field required                | **Yes**   |
| Add new required field                      | **Yes**   |
| Narrow constraints (enum, min/max, pattern) | **Yes**   |
| Widen constraints                           | No        |
| Change description                          | No        |

### Template-level incompatibility

| Situation                                | Incompatible for this template? |
| ---------------------------------------- | ------------------------------- |
| Removed field that template uses         | **Yes**                         |
| Changed type of field that template uses | **Yes**                         |
| Removed field that template does NOT use | No                              |
| Field made required                      | No (template provides it)       |
| Constraint narrowed                      | No (doesn't affect rendering)   |

## Referenced Paths Extraction

`TemplatePathExtractor` walks the template's node/slot graph and extracts all data contract paths from expressions. Computed on every template version save.

### Expression sources

- Loop/datalist/datatable: `node.props["expression"]` with `itemAlias` scoping
- Conditional: `node.props["condition"]`
- QR code: `node.props["value"]`
- Text content: inline TipTap expression nodes

### Loop alias resolution

Inside a loop on `orders` with `itemAlias: "order"`, the expression `order.name` resolves to the root path `orders[*].name`. Nested loops compound: `orders[*].items[*].price`.

### Stored format

`template_versions.referenced_paths` as JSONB:

```json
["customer.name", "orders", "orders[*].total", "orders[*].items[*].price"]
```

Non-nullable, defaults to `[]`. `Set<String>` in Kotlin.

## Data Model

### `contract_versions` table

```sql
CREATE TABLE contract_versions (
    id INTEGER NOT NULL,            -- Sequential 1-200
    tenant_key, catalog_key, template_key,
    schema JSONB,                   -- JSON Schema for strict validation
    data_model JSONB,               -- JSON Schema for visual editor
    data_examples JSONB DEFAULT '[]',
    status VARCHAR(20) DEFAULT 'draft',  -- draft | published
    created_at, published_at, created_by
);
```

One draft per template (unique partial index). Published contract index for fast lookups.

### `template_versions.contract_version` FK

```sql
ALTER TABLE template_versions ADD COLUMN contract_version INTEGER;
-- FK to contract_versions, no ON DELETE CASCADE (contract versions are never deleted individually)
```

### `template_versions.referenced_paths`

```sql
ALTER TABLE template_versions ADD COLUMN referenced_paths JSONB NOT NULL DEFAULT '[]';
```

## UI Integration

### Data contract editor

- Shows contract version badge (e.g., "v2 draft") with color-coded status
- Save callbacks route to `PATCH /contract/draft` endpoint
- Contract version history dialog via `GET /contract/versions/history`

### Variant version history

- "Contract" column shows which contract version each template version uses
- Versions on an older contract than the latest published are visually flagged

### Routes

```
POST  /{catalogId}/{id}/contract/draft            — create draft
PATCH /{catalogId}/{id}/contract/draft            — update draft
POST  /{catalogId}/{id}/contract/publish          — publish (with confirmation)
GET   /{catalogId}/{id}/contract/versions         — list versions (JSON)
GET   /{catalogId}/{id}/contract/versions/history — version history dialog (HTMX)
```
