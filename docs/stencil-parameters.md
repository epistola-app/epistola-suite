# Stencil Parameters — Design

> Status: preview (v0.20) — gated behind the `stencil-parameters` feature toggle (default off; enable at `/tenants/{tenantId}/features`)
> Decision record: [`adr/0002-stencil-parameters.md`](./adr/0002-stencil-parameters.md)

This document specifies the stencil-parameters feature shipped in
`feature/stencil-with-parameters`. The alternatives considered and the
rationale for the design decisions live in the ADR linked above. Read that
first if you want to know _why_; this file focuses on _what_ and _how_.

## Feature toggle

Authoring is gated by the `stencil-parameters` feature toggle (see
[`feature-toggles.md`](./feature-toggles.md)). When the toggle is off,
the editor hides the inspector's "Define parameters…" button, the
per-instance "Parameters" section, and the picker dialog's binding step.
The render pipeline remains permissive: existing stencils with parameter
schemas still render correctly when the toggle is later flipped off.
The follow-up work in §9 below also lives behind this toggle until
complete.

## 1. Summary

A stencil version can declare typed **input parameters** (a JSON Schema
`{type:"object", properties:{...}, required:[...]}` stored on
`StencilVersion.parameter_schema`). When a template embeds a stencil, the
consumer **binds** each parameter to a JSONata expression evaluated against
the available context — the template's data model, surrounding `for-each`
iteration variables, and engine-provided values like `sys.pages.current`.

At render time the bindings evaluate **lazily**: the renderer pushes a
`params` map onto the render context, and expressions inside the stencil's
content read `params.<name>` the same way they'd read `customer.name` or
`item.price`.

Parameters are orthogonal to placeholders. A stencil can have both:
placeholders are content slots filled by the consumer; parameters are
typed scalars/lists fed in by JSONata expressions.

## 2. Document model

### 2.1 Schema on the stencil version

A new column `stencil_versions.parameter_schema` (JSONB, nullable) stores
the parameters as a JSON Schema. NULL means "no parameters" — semantically
identical to `{"type":"object","properties":{}}`.

V1 supports only the primitives users actually need:

```jsonc
{
  "type": "object",
  "properties": {
    "recipientName": { "type": "string", "description": "Recipient", "default": "Anonymous" },
    "pageBreakBefore": { "type": "boolean" },
    "tags": { "type": "array", "items": { "type": "string" } },
    "deliveryDate": { "type": "string", "format": "date" },
  },
  "required": ["recipientName"],
}
```

Type rules (`ParameterSchemaValidator`):

- Property names match `^[a-z][a-zA-Z0-9_]{0,63}$` (camelCase allowed,
  because they surface as `params.recipientName`). Reserved names rejected:
  `params`, `item`, `sys`, `index`, and anything ending in
  `_index|_first|_last`.
- Each property's `type` is one of `string` / `number` / `integer` /
  `boolean`, optionally with `format: "date"` or `"date-time"`, **or**
  `array` whose `items.type` is one of those primitives.
- Defaults must match the declared type.
- Anything else (nested objects, `oneOf`, enums, …) is rejected; the
  storage stays canonical JSON Schema, so v2 can lift these without a
  migration.

### 2.2 Per-instance prop keys on the consumer node

When a template embeds a stencil, three props on the **stencil node**
carry parameter state:

```jsonc
{
  "id": "<NodeId>",
  "type": "stencil",
  "props": {
    "stencilId": "letter",
    "version": 3,
    "parameterBindings": {
      "recipientName": "customer.name",
      "tags": "['vip', 'priority']",
    },
    "paramsAlias": "params", // optional; defaults to "params"
    "parameterSchemaSnapshot": {
      /* copy of the source version's parameter_schema */
    },
  },
}
```

Prop key ownership:

- `parameterBindings` and `paramsAlias` are **component-agnostic** and live
  in `templates.model.NodeParameterKeys`. Any future parametrised component
  type uses the same keys.
- `parameterSchemaSnapshot` is **stencil-specific** and lives in
  `stencils.StencilNodeKeys`. Stencils are dynamic components — each
  version has its own schema — so the snapshot is the only way to make the
  schema available to the renderer / editor scope provider without a DB
  lookup. A future _static-parametrised_ component (e.g. a hypothetical
  "snippet") would supply a literal schema from its `ComponentDefinition`
  instead and not need the snapshot.

### 2.3 The `params` namespace

At render time, every parametrised node pushes a named scope onto
`RenderContext.parameterScopes` — a map keyed by alias. The default alias
is `params`, configurable via `paramsAlias`. `effectiveData` exposes each
named scope as a top-level key:

```kotlin
val effectiveData: Map<String, Any?>
    get() {
        var result = data
        if (systemParams.isNotEmpty()) result = result + ("sys" to systemParams)
        for ((alias, values) in parameterScopes) result = result + (alias to values)
        return result
    }
```

Nested parametrised nodes with **different** aliases coexist:

```
template
  └─ stencil "letter" (paramsAlias: "letter")
        └─ ...content with `letter.title`...
              └─ stencil "footer" (paramsAlias: "params" — default)
                    └─ ...content can read both `letter.title` and `params.year`
```

Same-alias nesting **intentionally shadows**, mirroring how
`for-each.itemAlias` works.

## 3. Validation pipeline

Three validators run on every content-mutating command:

1. **`ParameterSchemaValidator`** — schema-shape rules (Section 2.1). Runs
   from `Create`/`UpdateStencilDraft`/`PublishStencilVersion`.

2. **`PlaceholderValidator.validateStencilBindingShape`** — local check
   that `parameterBindings` is `Map<String, String>` with valid name slugs
   and non-blank values, and `paramsAlias` is a string. Runs from
   `validateAsStencilDefinition` and `validateAsTemplate`.

3. **`NodeParameterBindingValidator`** — cross-document check that every
   binding key references a declared parameter, every required parameter
   either has a binding or a `default`, and every bound expression is
   syntactically valid JSONata (`NODE_PARAMETER_BINDING_SYNTAX_INVALID`).
   Resolves the schema via `NodeParameterSchemaProviderRegistry`, which
   dispatches by node type to a provider Spring-bean. Runs from
   `UpdateDraft` and `UpdateStencilInTemplate`. Blank/empty/non-string
   binding values are skipped here (validator #2 rejects them first via
   `NODE_PARAMETER_BINDING_EMPTY`), so a blank **required** binding reports
   the precise `NODE_PARAMETER_BINDING_MISSING_REQUIRED` rather than a
   misleading syntax error.

Each failure carries a first-class machine code (`ValidationException.code`,
a `ValidationCode` enum value) — no longer a `SCREAMING_CODE:` prefix inside
the message. A single mapper (`ValidationException.toValidationErrorResponse()`)
emits the same `{ code, message, errors[] }` body on both the REST
`ApiExceptionHandler` and the UI draft-save route, and the editor switches on
`code` (never regex-parsing the message). That mapper is the seam for the
planned RFC 7807 `application/problem+json` migration.

> **Dual-parser note.** The backend parses JSONata with `com.dashjoin:jsonata`
> (Java) while the editor uses the `jsonata` npm package. The two grammars can
> disagree on edge cases; the backend is authoritative and surfaces any
> mismatch inline at save time. This divergence is a known, accepted trade-off
> of the SSR + client-editor split.

## 4. Render-time evaluation

`StencilNodeRenderer.render` does three things in order:

1. Recursion guard — push the stencil's `stencilId` onto
   `ancestorStencilIds`, check it isn't already there.
2. Resolve the schema via `RenderContext.parameterSchemaProvider(node, doc)`
   — production-wired through `NodeParameterSchemaProviderRegistry` (Spring),
   which dispatches by node type.
3. `ParameterScope.push(node, schema, context)` — evaluates each binding via
   `CompositeExpressionEvaluator` (= JSONata), applies defaults, returns a
   context whose `parameterScopes[alias]` is the new map. Required params
   without binding nor default throw in `STRICT` mode and yield null in
   `PREVIEW` mode.

`ParameterScope.push` is **component-agnostic**: the same one-liner adds
parameter support to any future renderer.

## 5. Two-pass detection

`TwoPassAnalyzer.collectExpressions` walks every node's expression-bearing
props (`condition`, `expression`, `value`), inline ProseMirror content, **and**
`parameterBindings`. A binding to `sys.pages.total` correctly triggers
two-pass rendering even when the stencil's own content doesn't reference
`sys.*` directly.

## 6. Upgrade semantics

`StencilContentReplacer.upgradeStencilInstances` preserves bindings whose
parameter names still exist in the new version's schema:

- **Preserved bindings** stay on the stencil node's `parameterBindings`.
- **Dropped bindings** (param removed from the new schema) are reported
  per stencil node id in `UpgradeResult.droppedBindings` so the UI can
  surface them.
- **Newly required, unbound params** without a default are reported in
  `UpgradeResult.unboundRequired`. The user must bind them before
  rendering produces sensible output.

The `parameterSchemaSnapshot` prop is refreshed to the new version's
schema (or removed entirely if the new version has no schema).

## 7. Editor flows

### Flow A — Stencil author defines parameters

In the stencil editor (draft mode), the inspector shows a
**"Define parameters… (N)"** button. Clicking it opens a modal
(`openParameterDefinitionsDialog`) hosting `<stencil-parameter-definitions-panel>`
— a two-panel layout matching the data-contract editor:

- **Left** — compact list of parameters: name + type chip, required dot.
- **Right** — detail form: name input, type dropdown, "list" toggle,
  required toggle, description textarea, default-value input with
  type-aware placeholder.

Saving emits `parameter-schema-change` with the canonical JSON Schema. The
inspector writes it to `node.props.parameterSchemaSnapshot` so subsequent
render walks see it; `saveDraft` (in `stencil-actions.ts`) forwards the
snapshot to `PUT /draft` for persistence on `StencilVersion.parameter_schema`.

### Flow B — Template author binds parameters

When inserting a stencil whose chosen version has parameters, the picker
dialog gains a **fourth step** (`stencil-step-bindings`) showing one row
per declared parameter (`renderBindingRow` in `binding-row.ts`):

- Name + type/required badge, description if present.
- A JSONata input pre-populated from any prior binding.
- A "…" button that opens the full `openExpressionDialog` (builder + code
  modes, live preview, custom functions) with autocomplete sourced from
  the available `fieldPaths` at the insertion point and a type-aware
  filter.

Required parameters block confirmation when their expression is empty.

### Flow C — Re-edit bindings post-insert

The stencil node's inspector shows a **"Configure parameters…"** button
that opens `openParameterBindingsDialog`. Same per-row UX as the picker's
step 4 (sharing `renderBindingRow`), plus an alias input at the top so
nested parametrised nodes can be disambiguated.

### Editor canvas preview

`buildParameterScope` (the editor's scope provider) populates the `params`
namespace synchronously with one of:

1. **Cached value** — populated by an async JSONata evaluation
   (`evaluateExpression`) keyed on `(stencilNodeId, alias, name, expression)`.
2. **Schema default** — when the cache is still empty.
3. **Synthetic `<paramName>` placeholder** — last resort, so authors editing
   a stencil draft (where no bindings exist yet) see something useful.

When the async evaluation resolves, `ExpressionNodeView.refreshAll()` fires,
every `{{...}}` chip re-evaluates against the now-populated context, and
the canvas converges to the resolved value. The cache invalidates on
`example:change` and structural `doc:change` events.

## 8. Critical files

| Concern                       | Path                                                                                                        |
| ----------------------------- | ----------------------------------------------------------------------------------------------------------- |
| DB column                     | `modules/epistola-core/src/main/resources/db/migration/V28__stencil_parameter_schema.sql`                   |
| Generic prop keys             | `modules/epistola-core/src/main/kotlin/.../templates/model/NodeParameterKeys.kt`                            |
| Stencil-specific snapshot key | `modules/epistola-core/src/main/kotlin/.../stencils/StencilNodeKeys.kt`                                     |
| Schema validator              | `modules/epistola-core/src/main/kotlin/.../templates/validation/ParameterSchemaValidator.kt`                |
| Binding validator             | `modules/epistola-core/src/main/kotlin/.../templates/validation/NodeParameterBindingValidator.kt`           |
| Schema provider registry      | `modules/epistola-core/src/main/kotlin/.../templates/validation/NodeParameterSchemaProvider.kt`             |
| Stencil schema provider       | `modules/epistola-core/src/main/kotlin/.../stencils/validation/StencilNodeParameterSchemaProviderConfig.kt` |
| Upgrade preservation          | `modules/epistola-core/src/main/kotlin/.../stencils/model/StencilContentReplacer.kt`                        |
| Render-time scope push        | `modules/generation/src/main/kotlin/.../pdf/ParameterScope.kt`                                              |
| Stencil renderer wiring       | `modules/generation/src/main/kotlin/.../pdf/StencilNodeRenderer.kt`                                         |
| Render context                | `modules/generation/src/main/kotlin/.../pdf/RenderContext.kt`                                               |
| Two-pass detection            | `modules/generation/src/main/kotlin/.../pdf/TwoPassAnalyzer.kt`                                             |
| Editor scope provider         | `modules/editor/src/main/typescript/engine/parameter-scope.ts`                                              |
| Async eval cache              | `modules/editor/src/main/typescript/engine/parameter-evaluation-cache.ts`                                   |
| Author panel (Lit)            | `modules/editor/src/main/typescript/components/stencil/StencilParameterDefinitionsPanel.ts`                 |
| Definitions dialog            | `modules/editor/src/main/typescript/components/stencil/parameter-definitions-dialog.ts`                     |
| Bindings dialog               | `modules/editor/src/main/typescript/components/stencil/parameter-bindings-dialog.ts`                        |
| Shared row helper             | `modules/editor/src/main/typescript/components/stencil/binding-row.ts`                                      |
| Picker dialog (step 4)        | `modules/editor/src/main/typescript/components/stencil/stencil-picker-dialog.ts`                            |
| Inspector                     | `modules/editor/src/main/typescript/components/stencil/StencilInspector.ts`                                 |

## 9. Known limitations (future work)

- **Catalog round-trip** — `epistola-catalog` import/export does not yet
  carry `parameterSchema`. Stencils exported via the catalog will lose
  their schema on round-trip. (Tracked separately.)
- **Public REST API contract** — the internal `StencilHandler` accepts
  `parameterSchema`, but the public `StencilVersionDto` in
  `epistola-contract` doesn't expose it yet.
- **Alias collision warnings** — the validator hard-rejects reserved
  aliases (`sys`, `item`, `index`), but doesn't warn when a chosen alias
  collides with a visible ancestor scope's alias (e.g. picking `letter`
  inside another `letter`-aliased stencil silently shadows).
- **Snapshot drift in draft mode** — when an author edits a stencil
  draft, the snapshot on the consuming node carries the _old_ schema
  until the draft is published and the consuming template upgrades.
- **Nested-object parameters** — v1 rejects `{type:"object"}` properties.
  v2 can lift this without a migration.
