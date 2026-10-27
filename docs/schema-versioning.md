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

- The template's content (`resource.dataModel`, [ADR 0038](adr/0038-catalogs-in-2-0.md)) — the JSON
  Schema, absent when the template has none.
- `resource.dataExamples` — the examples, each with a key; at least one is required.

The data paths a variant's model reads are not stored: `TemplatePathExtractor` computes them from
the model when they are needed (the 1.x `template_variants.referenced_paths` column was dropped by
the 2.0 migration chain).

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
   expressions reference, computed from the model.
3. **Template compatibility** (`TemplateCompatibilityEvaluator`): cross-references a variant's
   referenced paths against an old and a new schema, reporting `FIELD_REMOVED` and
   `TYPE_CHANGED` only for fields the template uses.

These are pure comparisons, and nothing in the application calls the extractor or the evaluator at
the moment: with no contract versions there is no publish step that runs them, and the web UI's
contract editor is still being moved to this model. `ChangeKindEvaluator` uses
`SchemaCompatibilityChecker` alone.

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

### Output format

`TemplatePathExtractor` returns the paths as a set:

```json
["customer.name", "orders", "orders[*].total", "orders[*].items[*].price"]
```

`Set<String>` in Kotlin, empty when the model reads no data.
