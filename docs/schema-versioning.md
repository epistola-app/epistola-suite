# Data Contract Storage and Compatibility

## Overview

A template's data contract (the JSON Schema defining what data a template accepts, plus its test
data examples) is part of the template, edited in place. Since 2.0 there are no contract versions:
the catalog release is the only version (contract ADR 0002), and a release carries each template's
contract with the rest of its content.

For the author-facing workflow, visual editor support, examples, Autofill, and known limitations,
see [Data Contracts](data-contracts.md). This page describes how the contract is stored and checked.

## Package Structure

```
templates/
  contracts/
    commands/UpdateDataContract.kt     # Replace schema and/or examples
    queries/GetDataContract.kt         # The template's contract
    SchemaCompatibilityChecker.kt      # Schema structural diff
    TemplateCompatibilityEvaluator.kt  # Referenced paths vs an old and a new schema
    SchemaPathNavigator.kt             # JSON Schema tree navigation
  commands/UpdateDataExample.kt, DeleteDataExample.kt
  analysis/
    TemplatePathExtractor.kt           # Extracts data paths from expressions
    TemplateCompatibilityResult.kt
```

## Storage

- `document_templates.data_model` — the JSON Schema, `NULL` when the template has none.
- `document_templates.data_examples` — the examples, each with a key; at least one is required.
- `template_variants.referenced_paths` — the data paths the variant's working model reads,
  recomputed on every model save.

`UpdateDataContract` validates the schema and property names, requires an example, and validates
every example against the schema; with `forceUpdate` the failures come back as warnings instead.

A variant's [ready mark](catalog-versioning.md#ready-marks) covers its template's data contract, so
editing the contract makes every variant of the template stale, and the catalog cannot be released
until they are marked ready again.

The 2.0 upgrade gave each template its draft contract, else its latest published one. The old
`contract_versions` table stays as history nobody reads.

## Compatibility Checking

1. **Schema structural diff** (`SchemaCompatibilityChecker`): compares two schemas for field
   removal, type changes, required changes and constraint narrowing.
2. **Template field usage** (`TemplatePathExtractor`): extracts which data paths a template's
   expressions reference, stored as `referenced_paths`.
3. **Template compatibility** (`TemplateCompatibilityEvaluator`): cross-references a variant's
   `referenced_paths` against an old and a new schema, reporting `FIELD_REMOVED` and
   `TYPE_CHANGED` only for fields the template uses.

These are pure comparisons. With no contract versions there is no publish step that runs them; the
web UI's contract editor is still being moved to this model.

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

`TemplatePathExtractor` walks the template's node/slot graph and extracts all data contract paths
from expressions.

### Expression sources

- Loop/datalist/datatable: `node.props["expression"]` with `itemAlias` scoping
- Conditional: `node.props["condition"]`
- QR code: `node.props["value"]`
- Text content: inline ProseMirror expression nodes

### Loop alias resolution

Inside a loop on `orders` with `itemAlias: "order"`, the expression `order.name` resolves to the root path `orders[*].name`. Nested loops compound: `orders[*].items[*].price`.

### Stored format

`template_variants.referenced_paths` as JSONB:

```json
["customer.name", "orders", "orders[*].total", "orders[*].items[*].price"]
```

Non-nullable, defaults to `[]`. `Set<String>` in Kotlin.
