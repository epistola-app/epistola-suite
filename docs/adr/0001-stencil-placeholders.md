# ADR 0001: Stencil Placeholders

- **Status:** Proposed
- **Date:** 2026-05-06
- **Deciders:** Epistola team
- **Tags:** stencils, templates, document model

## Context

Stencils today are pure inclusions. A template embeds a stencil with a single
node and on insert the stencil's full `TemplateDocument` is copied (with
re-keyed IDs) into the template:

```json
{
  "id": "n-header",
  "type": "stencil",
  "slots": [],
  "props": { "stencilId": "company-header", "version": 1 }
}
```

There is no way for the embedding template to vary the stencil's content.
That makes stencils good for "identical block, used many places" but bad
for the much more common case: "same _layout_, different _content_".

We want stencils to expose **named placeholders** that the embedding
template fills, with the fill content appearing **at the placeholder's
location** in the layout (true WYSIWYG).

### Decision drivers

- **Intuitive authoring** — placeholders should be a first-class thing
  users drag into a stencil, name, and later fill in a template.
- **WYSIWYG in the editor** — fills must render at the placeholder's
  position, not in a separate sidebar.
- **No recursion.** A stencil cannot be placed inside a placeholder if
  the same `stencilId` already exists in its ancestor chain.
- **Maintainability** — minimise new concepts; reuse the existing
  node/slot model where possible.
- **Forward compatibility with stencil parameters** (typed scalar/structured
  data inputs, mapped from the embedding template's data model). Parameters
  are out of scope for v1, but the placeholder design must not preclude
  them.
- **Pre-prod**: backwards compatibility is not a constraint.

## Considered options

### Option A — First-class `placeholder` node (with two variants)

Introduce a new node type `placeholder` that lives **inside** a stencil's
`TemplateDocument`. It owns a single slot named `fill`. Today's
copy-and-rekey logic on stencil insert already brings placeholder nodes
into the template; the template stores fills as the slot's children.

Two variants:

- **A1**: fills inlined into the stencil's copy (in the placeholder's
  `fill` slot, at the placeholder's tree position).
- **A2**: fills hoisted onto the stencil node's `props.fills` map.

#### A1 — Pros

- Reuses the existing slot mechanism; no new "fills map" concept.
- Storage model unchanged at the macro level (still a flat node/slot graph).
- Renderer change is a one-liner (register a `PlaceholderNodeRenderer`
  that renders its slot like a container).
- Recursion check is a literal ancestor walk on a tree we already walk.
- Upgrade semantics are tractable: name-keyed fill preservation.
- Orthogonal to future stencil parameters (which would live on
  `props.parameterBindings`).

#### A1 — Cons

- Fills are duplicated per stencil instance (same as stencil content
  itself today — no regression).
- Re-keyed placeholders rely on the `name` prop for identity (intentional
  and preserved across re-keying).

#### A2 — Pros

- Stencil node becomes the "single fill boundary" — clean fence between
  locked layout and embedder content.

#### A2 — Cons

- Breaks the invariant "all block content lives in slots". Editor canvas,
  inspector, drag-drop, selection model, and JSONB tooling all depend on
  it. Net: messier in practice. **Reject in favour of A1.**

### Option B — Fills map on the stencil node

The embedding stencil node carries `props.fills: { name → fragment }`.
The stencil's content references placeholders by name; renderer looks up
fills at render time.

#### B — Pros

- Fills are clearly the embedder's data, not co-mingled with re-keyed
  stencil content.
- Stencil node is the single fill boundary.

#### B — Cons

- Two graphs at the same level; renderer must dispatch through `name`.
- Recursion check has to walk into `props.fills` recursively — same
  effective complexity as A but harder to spot in code that today only
  walks slots.
- Editor must learn that block content can also live in `props.fills`.
  Breaks the "all content in slots" invariant.
- All existing tooling (`FindStencilUsages` JSONB queries, search,
  find-and-replace) needs a second code path.

Net: cleaner conceptual model, much messier in the codebase. **Reject.**

### Option C — Reference-only embedding (no copy on insert)

Stencils stop being inlined. The embedding stencil node stores
`{ stencilId, version, fills: { name → fragment } }`; the renderer and
editor load the stencil's content lazily and merge fills at render time.
One source of truth: the stencil version.

#### C — Pros

- Upgrades are pointer flips, not re-keyed content rewrites.
- Smaller template documents.
- Conceptually pure.

#### C — Cons

- Massive breaking change. Every renderer pass, every editor selection
  model, every JSONB query (`FindStencilUsages`, `GetStencilUsageDetails`)
  assumes inlined copies.
- Loses the existing in-place stencil-draft editing (`isDraft` mode).
- Recursion check now requires loading every referenced stencil version
  from the catalog (graph walk, not tree walk).
- Orthogonal to placeholders — the upgrade-churn problem it fixes is real
  but is not _why_ we're adding placeholders.

Net: a real architectural improvement, but the wrong moment. **Reject for
v1.** Revisit when upgrade churn becomes painful enough to justify the
migration cost.

### Option D — Data-binding extension (placeholders = data fields)

Treat placeholders as special "rich-content" entries on the stencil's
data model. The stencil's `dataModel` (today: a JSON Schema for plain
data) gains a `richContent` extension; templates that embed the stencil
provide the rich content alongside ordinary data.

#### D — Pros

- Reuses the data-contract pipeline.

#### D — Cons

- Conflates _content_ (block layout, by structure) with _data_ (values,
  by schema). They serialise differently, validate differently, render
  differently, and are authored in different editors. Forcing them
  together breaks both.
- Stencils today have no `dataModel` at all — adding one just to host
  placeholders is a much bigger feature.
- Forecloses on the future "stencil parameters" feature, which _will_
  use the data-contract pipeline for actual data values.

Net: wrong abstraction. **Reject.**

## Decision

**Option A1.** First-class `placeholder` node type, fills stored as
children of the placeholder's `fill` slot, recursion enforced at three
layers (picker UI, server validator, renderer).

### Why A1

- It is the **smallest delta** to the existing model. The renderer,
  editor canvas, inspector, drag-drop, JSONB usage queries, and the
  upgrade pipeline keep working with one new node type registered.
- It gives WYSIWYG fills **without** inventing a new mode toggle: the
  template editor, by default, already shows fills inline. The "edit
  vs. read" framing in the original proposal collapses into "are you
  editing the stencil definition or a template that uses it?".
- Recursion becomes a tree-walk problem, which all existing tools
  already do.
- It is **orthogonal to future stencil parameters**. Parameters will
  live on `props.parameterBindings` of the embedding stencil node;
  placeholders live in the slot graph. The two never collide.
- It cleanly leaves the door open for the bigger Option C refactor
  later, without prejudging it.

## Consequences

### Positive

- One new node type (`placeholder`) and one new node renderer; existing
  pipelines untouched.
- No Flyway migration — `template_versions.template_model` and
  `stencil_versions.content` are already JSONB and accept new node
  types as schema-level additions.
- Backend validation, JSON Schema, REST API DTOs all extend additively.

### Negative / accepted trade-offs

- Fill content duplicates per stencil instance (same property as
  stencil content itself today; no regression).
- Stencil version upgrade pipeline (`UpdateStencilInTemplate` /
  `StencilContentReplacer`) gains a name-keyed fill-preservation step
  with explicit handling for removed-placeholder warnings.
- Editor selection model must distinguish "locked stencil layout" from
  "editable placeholder fill" inside a single locked-stencil subtree.

### Forward-compatibility commitments (made in v1, behaviour deferred)

- **Reserve `props.parameterBindings`** on stencil nodes for the future
  parameters feature; reject arbitrary use of that key in v1.
- **Reserve `dataModel`** on stencil-version content for the same reason.
- Keep `RenderContext` data-scope plumbing factored so a per-stencil
  scope stack is a small follow-up, not a rewrite.
- Do not promote placeholders into a typed-data framing — they remain
  structural (block content). "A date here" is a future parameter, not
  a placeholder.

## Addendum (post-v1) — placeholder gets two slots

After v1 shipped, three user-facing issues surfaced that all traced back to
the design's choice to collapse the stencil's default content and the
embedding template's override into a single `fill` slot:

1. Re-entering edit mode on a stencil instance with overrides showed the
   overrides, not the original default — and saving promoted the overrides
   into the stencil definition.
2. Editing the "default" did not have a way to leave overrides in place.
3. Clearing all overrides left an empty placeholder with no path back to
   the default.

The original ADR considered Approach A (single slot, defaults inlined)
versus alternatives that separated default and override; A1 was chosen for
the smallest delta to the existing model. With three bugs hitting the same
weakness, **the model is updated to two slots**: `default` (frozen at insert
time, set by the stencil author) and `fill` (the template's override).
Renderer falls back from `fill` to `default` when `fill` is empty.

This is still A1 in spirit (placeholder is a node with named slots, copied
on insert, recursion-tree-walkable, server fill-preservation by name) — only
the storage shape is refined. The change is **breaking** for any documents
authored before the refinement, but the project is pre-prod so no migration
is provided.

The other rejected options (B, C, D) remain rejected for the same reasons.

## References

- Detailed design: [`../stencil-placeholders.md`](../stencil-placeholders.md)
- Current stencil domain: `modules/epistola-core/src/main/kotlin/app/epistola/suite/stencils/`
- Stencil content replacer (upgrade path):
  `modules/epistola-core/.../stencils/model/StencilContentReplacer.kt`
- Stencil component definition (editor):
  `modules/editor/src/main/typescript/components/stencil/stencil-registration.ts`
- ProseMirror expression-atom (template for future inline placeholders):
  `modules/editor/src/main/typescript/prosemirror/schema.ts:16-43`
