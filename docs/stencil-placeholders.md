# Stencil Placeholders — Design

> Status: implemented; placeholder model refined post-v1 to use two slots
> (default + fill). See §2.1 and the ADR addendum.
> Decision record: [`adr/0001-stencil-placeholders.md`](./adr/0001-stencil-placeholders.md)

This document specifies the chosen design for stencil placeholders. The
alternatives considered and the rationale for choosing this one live in the
ADR linked above. Read that first if you want to know _why_; this file
focuses on _what_ and _how_.

## 1. Summary

A new node type `placeholder` is added to the document model. It lives
inside a stencil's `TemplateDocument` at the position where the placeholder
should appear, and owns one slot (`fill`) whose children are the fill
content.

When a template embeds a stencil, today's copy-and-rekey logic carries the
placeholder nodes along. The embedding template fills a placeholder by
adding nodes to its `fill` slot — exactly like editing any other slot.
There is no separate "stencil parameters" panel; fills are authored in
place.

A stencil cannot be placed inside a placeholder if the same `stencilId`
already appears in its ancestor chain. This is enforced in the editor
picker, server-side validators, and the renderer.

## 2. Document model

### 2.1 The `placeholder` node

A placeholder owns **two slots**: `default` (set by the stencil author) and
`fill` (set by the embedding template). Code dispatches on slot **name**, not
position.

```jsonc
{
  "id": "<NodeId>",
  "type": "placeholder",
  "slots": ["<defaultSlotId>", "<fillSlotId>"],
  "props": {
    "name": "<slug, [a-z][a-z0-9-]{0,63}>", // required; unique within stencil
    "description": "<string, optional>",
    "kind": "block", // reserved; only "block" in v1
  },
  "styles": {
    /* container-style props, optional */
  },
}
```

- **`default` slot** — children are the stencil author's default content.
  Set in stencil-edit mode; carried along (re-keyed) when the stencil is
  inserted into a template; frozen in template-fill mode (read-only).
- **`fill` slot** — children are the embedding template's override.
  Empty in newly-inserted stencils. The user populates this slot to override
  the default. Clearing the fill reverts to the default.

**Renderer fallback:** the PDF renderer (`PlaceholderNodeRenderer`) renders
`fill.children` if non-empty, otherwise renders `default.children`. The two
slots are **independent storage** — editing one does not affect the other.
This is a deliberate change from the v1 design (single `fill` slot conflated
with default), made post-shipping after surfacing several user-visible
issues with the conflated model.

### 2.1.1 Why two slots

The single-slot v1 collapsed default-content and template-override into the
same storage, with three resulting bugs:

1. Re-entering "Start Editing" on a stencil instance with overrides showed
   the overrides (not the original default).
2. Editing what looked like "the default" and saving promoted the override
   into the stencil definition.
3. Removing all override content left an empty placeholder; there was no
   way to fall back to the default.

Two slots solve all three by giving each role its own storage; the editor
canvas dispatches on context (stencil-author vs template-fill, computed by
`placeholderContext` walking ancestors).

### 2.2 Where placeholder nodes are allowed

- **Inside stencils:** anywhere a normal block-level container child is
  allowed.
- **Inside another placeholder's `fill` slot at the stencil-definition
  level:** **forbidden** (a placeholder defining another placeholder is
  meaningless).
- **Inside another placeholder's `fill` slot at the template level:**
  allowed (the user fills a placeholder with content that contains a
  stencil that itself has placeholders).

### 2.3 Naming and uniqueness

- Within a single stencil version, every `placeholder` node's `props.name`
  must be unique. Enforced in `CreateStencilVersion` /
  `UpdateStencilDraft`. Surfaced in the editor inspector.
- Across stencils, names are independent (no global namespace).
- Renaming a placeholder is _remove + add_. Fills are not auto-migrated.

## 3. Editor

### 3.1 Stencil author view (incl. in-template `isDraft` mode)

- New file: `placeholder-registration.ts` registers a `placeholder`
  component (palette entry "Placeholder", inspector with `name` and
  `description` fields, single slot named `"fill"`,
  `defaultProps: { name: "" }`).
- `renderCanvas`: a labelled bordered box ("Placeholder · _name_"), with
  the `fill` slot rendered inside as a small "default content" pane so
  the stencil author can preview what the default will look like.
- Inspector validation: live-check name uniqueness within the stencil;
  red border + message on conflict.

In `isDraft` mode (a stencil being authored in place inside a template),
placeholder authoring works identically to the dedicated stencil editor.
The user can add placeholders, reorder them, and edit their default
content. Filling placeholders is meaningless in this mode (there is no
embedding template above the stencil yet) — the slot is just labelled
"Default".

### 3.2 Template editor (stencil locked, placeholders editable)

Existing locked-stencil rendering (`stencil-registration.ts`,
`canvas-stencil-locked` class) is extended:

- Locked stencils still render their full subtree, but **placeholder nodes
  inside them remain selectable and editable**. The lock applies to
  _layout_, not _fills_.
- Empty placeholder: styled drop-zone showing the placeholder's name and
  (optional) description, plus an "insert here" affordance.
- Filled placeholder: fills render inline. A thin guides-style border
  appears only when the user toggles "Show placeholder boundaries" in the
  toolbar (off by default).

`StencilInspector` extension: when a stencil is locked in a template, the
inspector lists declared placeholders with their fill state (filled /
default) and offers a "reset to default" action per placeholder.

### 3.3 Stencil picker — recursion guard

`stencil-picker-dialog.ts` computes the ancestor stencil set for the
insert position before showing the list:

```ts
function ancestorStencilIds(doc: TemplateDocument, target: SlotId): Set<string> {
  const result = new Set<string>();
  let slotId: SlotId | null = target;
  while (slotId) {
    const slot = doc.slots[slotId];
    const parent = doc.nodes[slot.nodeId];
    if (parent.type === "stencil") result.add(parent.props.stencilId);
    slotId = findSlotContaining(doc, parent.id); // walks up
  }
  return result;
}
```

Stencils whose `stencilId` is in the set are rendered disabled with a
"would cause recursion" tooltip. Drag-and-drop applies the same check on
the drop target and rejects the drop with a toast.

## 4. Backend

### 4.1 Schema — no migration needed

`stencil_versions.content` and `template_versions.template_model` are
already JSONB. The Kotlin `Node` data class has `type: String` (no enum).
The new `placeholder` node type is just a new value of that field.

### 4.2 JSON Schema and contract

- **`@epistola.app/epistola-model/schemas/template-document.schema.json`:**
  add a `Placeholder` definition extending `Node` with a constrained
  `props` shape; add a `oneOf` so `Node` validates as
  `Placeholder | OtherNode`. Bump `epistola-model` minor version.
- **`epistola-contract/spec/components/schemas/`:** add a
  `PlaceholderNodeDto` definition; reference it from the `NodeDto`
  `oneOf`. Bump contract version.

### 4.3 Validators

In `JsonSchemaValidator` (and downstream command validation), after the
schema check, walk the document and assert:

1. Every `placeholder.props.name` is unique within the stencil version.
   Reject with `PLACEHOLDER_NAME_DUPLICATE`.
2. No placeholder is nested inside another placeholder's `fill` slot at
   the stencil-definition level. Reject with
   `PLACEHOLDER_NESTED_DEFINITION`.
3. No stencil node has a `stencilId` matching any of its ancestor
   stencils (recursion). Reject with `STENCIL_RECURSION`.

All three are tree walks.

### 4.4 Commands

- `CreateStencilVersion`, `UpdateStencilDraft`: run validators (1) and
  (2) on the stencil's content.
- Any command that accepts a template draft body (incl. template
  `UpdateTemplateDraft`, `UpdateStencilInTemplate`): run validator (3).
- `UpdateStencilInTemplate` / `StencilContentReplacer`: implement
  fill-preservation across upgrades (see §6).

### 4.5 Queries — no change

`FindStencilUsages` and `GetStencilUsageDetails` query
`n.value ->> 'type' = 'stencil'`. Placeholder content is transparent
to them. A stencil used _inside a placeholder fill_ of another stencil
correctly counts as a usage — the desired behaviour.

### 4.6 Renderer

`generation/.../DirectPdfRenderer.kt` registers a
`PlaceholderNodeRenderer` whose behaviour is identical to
`ContainerNodeRenderer` (render the slot's children). One-line
registration.

The renderer also gains a recursion safety net: an
`ancestorStencilIds: Set<String>` on `RenderContext`, pushed on entering
a stencil node and checked for duplicates. Aborts with a clear diagnostic
on any duplicate. ~20 lines of code.

## 5. Recursion: full picture

| Layer            | What it does                                                                         | When it runs              |
| ---------------- | ------------------------------------------------------------------------------------ | ------------------------- |
| Picker UI        | Greys out recursive options; tooltip explains why                                    | Before insert             |
| Drag-and-drop UI | Same ancestor walk on the drop target; rejects the drop with a toast                 | On drop                   |
| Server validator | Tree walk on every template / stencil-in-template save                               | On command                |
| JSON Schema      | Cannot enforce ancestry — leaves it to the validator; enforces structural invariants | On every contract request |
| Renderer         | `Set<StencilId>` ancestor stack; aborts with a diagnostic on duplicate               | On render                 |

The first two are UX, the third is correctness, the last is defence in
depth. The fourth is a no-op for ancestry.

## 6. Stencil version upgrades (`UpdateStencilInTemplate`)

`UpdateStencilInTemplate` already re-keys the new content into each
embedding stencil node. It gains a fill-preservation step:

1. Before replacing, walk the old children, find every `placeholder` node,
   and capture `{ name → children-of-fill-slot }` (deep, _not_ re-keyed
   yet).
2. Re-key the new content into place as today.
3. Walk the new content, find every `placeholder` node by name. If a
   capture exists for that name, splice the captured children into the
   new placeholder's `fill` slot (re-keying them now). Otherwise leave
   the new default in place.
4. If captured fills have no matching placeholder in the new version, do
   _not_ drop them silently — return them in the command result so the UI
   can warn. v1 default behaviour: discard with a warning.

The command's return type grows a `droppedFills: List<{ name, contentSummary }>`
field for the UI.

## 7. Authoring-in-template (`isDraft`) interaction

When a stencil sits in `isDraft` mode inside a template, the user is
editing the stencil definition. They can:

- Add a placeholder node (defines a new placeholder).
- Reorder, restyle, delete placeholders.
- Edit _default content_ inside a placeholder's `fill` slot.

They _cannot_ "fill" placeholders in this mode — there is no embedding
template above this stencil yet. The "fill" UX only makes sense once the
stencil is published and another template embeds it. In `isDraft` mode
the placeholder's `fill` slot is rendered as a normal editable area
labelled "Default".

## 8. Migration

Existing stencils have no placeholder nodes — nothing to migrate. JSON
Schema and contract additions are additive. `epistola-model` and
`epistola-contract` bump minor versions. No Flyway migration.

## 9. Forward compatibility — stencil parameters

Stencils will eventually accept _typed scalar/structured data_ arguments —
e.g. a "header" stencil with a `date` parameter the embedding template
binds to its own `invoiceDate` field. **This is out of scope for v1**, but
the placeholder design must not foreclose on it.

The eventual shape:

- Stencil version content gains an optional `dataModel` (JSON Schema, same
  shape templates already use).
- Embedding stencil node gains an optional `props.parameterBindings`
  map: `{ paramName → Expression | LiteralValue }`. Expressions reuse the
  existing vocabulary (`jsonata`, `simple_path`, `javascript`).
- At render time, the renderer's `RenderContext` data scope is pushed when
  entering a stencil; bindings are evaluated against the parent scope to
  build the child scope; the stencil's expressions resolve against it;
  scope is popped on exit. The same ancestor stack already used for the
  recursion guard becomes the natural place for the data-scope stack too.
- In the editor, the stencil inspector grows a "Parameters" group below
  "Placeholders". Each parameter has a binding editor with two modes:
  literal value (typed input) or template variable (autocomplete of paths
  drawn from the template's own `dataModel`, filtered to compatible
  types).

### Why placeholders ≠ parameters

Placeholders carry **block content** (rich layout chunks) and live in the
slot graph. Parameters carry **scalar/structured data values** and live in
a typed schema on the stencil node's `props`. The two never overlap; this
is the same split every comparable system makes (Web Components: slots
vs. attributes; Vue: slots vs. props; Angular: `<ng-content>` vs.
`@Input()`).

### v1 commitments to keep the door open

1. **Reserve `props.parameterBindings`** on stencil nodes — document it
   as reserved, reject arbitrary props with that key in v1.
2. **Reserve `dataModel`** on stencil-version content — same.
3. **Keep `RenderContext` data-scope plumbing factored** so a stack is a
   small follow-up, not a rewrite. (Already a sane shape today; no v1
   change beyond awareness.)
4. **Do not promote placeholders into a typed-data framing.** They remain
   structural. Anyone who needs "a date here" today fills a placeholder
   with a text node and migrates to a parameter when that feature ships.

## 10. Test surface

- **Unit tests:**
  - placeholder-name uniqueness validator
  - recursion detector (positive, negative, deep-nested, multi-instance)
  - fill preservation across stencil upgrades (rename = remove+add,
    removed-placeholder warning, new-placeholder default)
- **Integration tests:**
  - end-to-end: create stencil with placeholders → publish → embed in
    template → fill → upgrade stencil → verify fills preserved → render
    to PDF
- **UI (Playwright):**
  - insert a stencil, fill a placeholder, render preview
  - attempt to insert the same stencil into its own placeholder — verify
    the picker hides it with a tooltip

## 11. Open questions

1. **Stencil parameters timing** — when do we actually build §9? Likely
   the iteration after placeholders ship. v1 only _reserves_ the keys
   (`props.parameterBindings`, stencil-version `dataModel`).
2. **Inline placeholders in rich text** — in v1? Recommendation: no.
   Block-level only; treat inline as a follow-up driven by user demand.
   The existing ProseMirror expression atom (`prosemirror/schema.ts:16-43`)
   is the obvious template if/when we add it.
3. **Required placeholders** — should authors mark a placeholder required?
   If so, what is the failure mode at render time? Recommendation: punt;
   everything is optional in v1.
4. **Repeating fills** — explicitly out of scope (the data-binding system,
   `datatable`, handles repetition).
5. **Default-vs-explicit-fill UX** — when a fill is structurally identical
   to the default, do we still flag it as a user fill? Today: yes, since
   the content is in the slot regardless. The "reset to default" inspector
   action could do a structural compare against the published version's
   default if we want to show the distinction.
6. **Placeholder name style** — kebab-case slug or any string?
   Recommendation: kebab-case slug for stability; show a friendly
   `description` separately.
7. **Default modified, then upgraded** — the user replaces a default with
   their own fill; the stencil author later changes the default. On
   upgrade, the user's fill is preserved (by name); the new default is
   _not_ surfaced. Recommendation: acceptable; surface a note in the
   upgrade dialog ("default content for placeholder X has changed in the
   new stencil version, but you have an explicit fill — your fill was
   kept").
