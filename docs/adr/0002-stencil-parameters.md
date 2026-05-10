# ADR 0002: Stencil Parameters

- **Status:** Accepted (implemented in v0.20)
- **Date:** 2026-05-09
- **Deciders:** Epistola team
- **Tags:** stencils, templates, document model, rendering

## Context

Stencils ship as inlined `TemplateDocument` fragments — useful for shared
layout, useless for anything that varies per-instance. Placeholders (ADR 0001) solved the "different content in the same layout" case, but only for
content slots, not typed scalars. Three concrete needs:

- A page header that wants the **current chapter title** (a string).
- A footer that wants the **total page count** (an integer that depends on
  two-pass rendering).
- An iteration row stencil that wants the **current item's price** (a
  number from a surrounding `for-each`).

We need a way for a stencil to declare typed inputs, and for the consuming
template to map each input to a JSONata expression evaluated against the
context at the insertion point.

### Decision drivers

- **Lazy evaluation** — `sys.pages.total` and iteration variables only
  resolve at render time, so binding values must not be baked at insert time.
- **Type-aware authoring** — the consumer needs to see what each parameter
  is (string vs number vs date) and have autocomplete narrow to compatible
  fields.
- **Forward-compatible storage** — v1 will support primitives + lists; v2
  may want nested objects. The on-disk format should not need migration.
- **Component-agnostic primitives** — stencils are the first concrete
  parametrised component, but we want future static-parametrised
  components (snippets, fragments) to plug in without a refactor.
- **WYSIWYG canvas preview** — the editor canvas should show resolved
  values, not raw `{{params.foo}}` text.
- **No circular dependency** — generation module must not depend on
  Jackson; the schema must be representable as a plain `Map`.
- **Pre-prod**: backwards compatibility is not a constraint.

## Decisions

### D1 — Bindings live on the consumer node, evaluated lazily at render time

Each stencil node carries `props.parameterBindings: Map<paramName, JSONata
expression>`. The renderer evaluates the bindings against the _outer_
context at render time and pushes a `params` map onto the render context.
Inside the stencil's content, expressions read `params.<name>` the same
way they read `customer.name` or `item.price`.

**Alternatives considered:**

- **Substitute statically at insert.** Rejected: would freeze
  `sys.pages.total` and iteration variables to their values at insertion,
  defeating the dynamic case which is the main motivation.
- **Bake into the inlined content.** Rejected for the same reason, plus it
  would lose the consumer's ability to re-bind without re-inserting.

### D2 — Schema as JSON Schema; v1 surfaces only primitives + lists

Store the parameters as a canonical JSON Schema
(`{type:"object", properties, required}`) on `StencilVersion.parameter_schema`.
The v1 author UI exposes only string / number / integer / boolean / date /
date-time, plus `array<primitive>`. The schema validator rejects
nested objects, `oneOf`, and other JSON Schema features.

**Alternatives considered:**

- **Custom DSL** (e.g. `recipientName: string required`). Rejected: every
  feature would need its own parser/serializer, and we'd be one step away
  from reinventing JSON Schema poorly.
- **Allow arbitrary JSON Schema in v1.** Rejected: the author UI for
  nested objects is a much bigger lift and not needed for v1 use cases.
  Storing canonical JSON Schema means v2 can lift restrictions without a
  migration.

### D3 — `params` is the default alias, configurable per consumer

Each parametrised node carries `props.paramsAlias` (default `"params"`).
The render context holds a map of named scopes
(`parameterScopes: Map<alias, Map<name, value>>`), each merged into
`effectiveData` under its own top-level key. Nested parametrised nodes
with **different** aliases coexist; nodes sharing an alias intentionally
shadow.

This mirrors `for-each.itemAlias` exactly — same precedent, same mental
model.

**Alternatives considered:**

- **Always `params.*`, no configuration.** Rejected: nested parametrised
  components (e.g. a stencil inside a stencil) would always shadow each
  other's params with no way to access the outer's.
- **Implicit parent traversal** (`$parent.params.foo`). Rejected: novel
  pattern, more confusing than the explicit alias.

### D4 — Generic primitives + stencil-specific data convention

`parameterBindings` and `paramsAlias` are component-agnostic prop keys
(`templates.model.NodeParameterKeys`). The schema-shape validator,
binding-shape validator, render-time `ParameterScope.push`, and editor
`buildParameterScope` are all component-agnostic.

The schema source differs per component type and is resolved through a
pluggable `NodeParameterSchemaProvider` (Spring registry keyed by node
type). Stencils carry `props.parameterSchemaSnapshot` because each version
has its own schema; a future static-parametrised component would supply a
literal schema from its `ComponentDefinition` instead.

**Alternatives considered:**

- **Bake the stencil-snapshot reading directly into each consumer.**
  Rejected: every new validator/renderer would duplicate the lookup. The
  provider registry inverts the dependency — adding a new parametrised
  component type is a new bean, not a new code path through every consumer.
- **Skip the abstraction; ship stencil-only v1.** Rejected: the cost of
  the provider pattern is low (~30 LOC) and the alternative would be a
  hard refactor when the second use case appears.

### D5 — Snapshot the schema onto the consuming node

The schema is duplicated from `StencilVersion.parameter_schema` to
`stencilNode.props.parameterSchemaSnapshot` at insert/upgrade time.

**Pros:**

- Renderer + validator + editor scope provider don't need a DB lookup.
- Insulates the consuming template from upstream stencil changes — the
  consumer sees a stable schema until they explicitly upgrade.
- Same pattern stencils already use to inline content.

**Cons:**

- Drift in draft mode: while a stencil author is editing a draft schema,
  consumers' snapshots still show the prior published version's schema
  until they upgrade. Acceptable; avoids spooky-action-at-a-distance.
- Storage cost: ~hundreds of bytes per consuming node. Negligible.

### D6 — Schema as `Map<String, Any?>` in the render module

The generation module has no Jackson dependency. The schema is therefore
passed around as `Map<String, Any?>` (Jackson's natural deserialization
shape for JSON content). The provider abstraction handles the conversion
from `JsonNode` (used by the validator) to `Map` (used by the renderer)
via `ObjectMapper.convertValue` in
`StencilNodeParameterSchemaProviderConfig`.

**Alternative:** add Jackson to `:modules:generation`. Rejected: the
module is intentionally minimal (iText + jsonata + a few helpers), and
pulling Jackson in for one feature would inflate every PDF render.

### D7 — Two-pass detector reads `parameterBindings`

`TwoPassAnalyzer.collectExpressions` was extended to harvest values from
`parameterBindings` so a stencil binding `params.totalPages` to
`sys.pages.total` correctly triggers two-pass rendering even when the
stencil's body never references `sys.*` directly.

**Alternative:** require the stencil's content to also reference the
two-pass system parameter. Rejected: the parameter binding _is_ a
deferred reference; making the consumer also place the reference
elsewhere would be confusing and easy to forget.

### D8 — Editor canvas previews use real async JSONata + cache

Initial implementation used a synchronous dot-path resolver
(`resolveSimplePath`), which couldn't handle JSONata literals,
concatenation, or function calls. Switched to the existing async
`evaluateExpression` (same library the renderer uses) backed by a
`(nodeId, alias, name, expr)` cache that triggers
`ExpressionNodeView.refreshAll()` when results land.

**Alternative:** keep `resolveSimplePath` and tell users complex
expressions don't preview. Rejected: the canvas should match the PDF.
A literal `'p2'` showing as `<param2>` while the PDF renders `p2` is a
bad surprise.

## Consequences

**Positive:**

- Stencils now cover the "same layout, different typed inputs" case fully.
- The generic primitives are ready for a future "snippet" or similar
  static-parametrised component.
- JSON Schema underneath means v2 features (nested objects, enums) can
  ship without a DB migration.

**Negative / deferred:**

- Catalog import/export round-trip not done — stencils exported via
  `epistola-catalog` lose their schema. Tracked.
- Public REST API contract (`epistola-contract`) doesn't expose
  `parameterSchema` yet. The internal handler does.
- No backend JSONata-syntax validation on save (deferred to v2).
- No alias-collision warnings against ancestor scopes (deferred).
- Snapshot drift in draft mode is by design but worth documenting in
  the "Define parameters…" UI.

## Implementation references

See [`stencil-parameters.md`](../stencil-parameters.md) §8 for the full
list of changed files.

Test coverage:

- `ParameterSchemaValidatorTest` — schema-shape rules.
- `NodeParameterBindingValidatorTest` — cross-document binding checks.
- `StencilContentReplacerTest` — binding preservation across upgrades.
- `ParameterScopeTest` — render-time scope semantics.
- `StencilParameterRenderTest` — end-to-end PDF render with bindings.
- `parameter-scope.test.ts` — editor scope provider with async eval.
- `TwoPassAnalyzerTest` — `parameterBindings` triggers two-pass.
