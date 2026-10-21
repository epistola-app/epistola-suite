# Stencils — Reusable Template Components

Stencils are reusable components that templates use by reference. They let you maintain common
elements (headers, footers, address blocks) in one place: a change to a stencil reaches every
template that uses it.

Decision record: contract
[ADR 0006](https://github.com/epistola-app/epistola-contract/blob/feature/v2/docs/adr/0006-stencil-instances-by-reference.md)
(stencil instances by reference, lexical scope). Placeholders and parameters have their own pages:
[stencil-placeholders.md](stencil-placeholders.md) and
[stencil-parameters.md](stencil-parameters.md).

## Concepts

### Stencil

A stencil is a named component in a catalog, with metadata (name, description, tags) and **one
editable working copy**:

- `content` — a template document;
- `parameterSchema` — the parameters an instance binds (JSON Schema), or none;
- `readsTemplateData` — whether its content also sees the using template's data (see
  [Scope](#scope)).

A stencil has no versions of its own. It is versioned with its catalog: releasing the catalog
captures every stencil's working copy, and a render from that release uses the stencils held by it.

### Stencil instance

A template uses a stencil through a `stencil` node that **names** it and holds only what the template
supplies: its parameter bindings and its fills. Nothing of the stencil's own content is stored in the
template. Wherever the template is shown or rendered, the instance is expanded from the stencil.

## Working with stencils

The commands live in `modules/epistola-core/src/main/kotlin/app/epistola/suite/stencils/`:

- `CreateStencil` — a new stencil with its working copy;
- `UpdateStencil` — changes name, description, tags, `content`, `parameterSchema` or
  `readsTemplateData`, each only when given. A schema declaring no parameters (`{}`, or one without
  properties) removes the schema, as does `clearParameterSchema`; leaving it out keeps it. REST and
  MCP follow the same rule, so a client saving only the content cannot drop the parameters;
- `ImportStencil` — a catalog import replaces the working copy with the imported stencil;
- `MarkStencilReady` — records that the working copy is reviewed, so the next release may capture
  it (see [ready marks](catalog-versioning.md#ready-marks)). Any later edit makes the mark stale.

Content is validated as a stencil document on create and update; the parameter schema by
`ParameterSchemaValidator`.

**Editing a stencil changes every template that uses it**, at once: previews read the working copy,
and the next catalog release captures it once the stencil is marked ready. There is nothing to
upgrade or move.

Over MCP the same operations are `list_stencils`, `get_stencil`, `list_stencil_usages`,
`create_stencil`, `update_stencil` and `mark_stencil_ready`; see [mcp.md](mcp.md).

In the web UI the stencil page shows the stencil's details, whether its content reads the using
template's data (a toggle), its parameter schema, whether its working copy is ready (with **Mark
ready** while it is not), and the variants that use it, each with whether it still fits and why
not. Content is edited in the template editor, which reads and saves the working copy through
`GET`/`PUT /tenants/{tenant}/catalogs/{catalog}/stencils/{stencil}/content` (see
[In the template editor](#in-the-template-editor)).

### In the template editor

The editor works on the expanded template (see [Collapse and expand](#collapse-and-expand)) and
inserts stencils by reference too:

- **Inserting.** The stencil picker lists stencils, not versions: picking one reads its working copy,
  and the stencils that one uses, and inlines them under the new instance with the same IDs the
  server's expansion gives (`{inst}:{nodeId}`). A stencil with parameters goes through a binding step
  first. **Create New** (offered only when the host can create stencils) makes an empty stencil and
  starts editing it in place.
- **Using.** An instance's content is the stencil's and is locked; only its placeholders' fills are
  the template's to edit. Saving the template stores the reference, its bindings and its fills.
- **Editing a stencil in place.** **Edit Stencil** (offered when the stencil's catalog can be changed here;
  otherwise the inspector says why not) unlocks the instance's content. **Save Stencil** writes the
  working copy — without the template's fills, with the stencils it uses stored by reference — and
  refreshes every other instance of the stencil in the document. **Save and Mark Ready** also marks it
  ready; **Stop Editing** shows the saved working copy again, dropping what was not saved. Editing is
  a state of the editor session, never stored with the template.
- **Recursion.** The editor refuses, before it happens, an insert or a move that would make a stencil
  contain itself: the picker disables the stencils the insertion point is already the content of, and
  refuses a picked stencil that uses one of them further down, saying which. The check is lexical, as
  the server's: a template's fill may hold the stencil it fills.

The host implements the editor's stencil callbacks (`StencilCallbacks` in
`modules/editor/src/main/typescript/components/stencil/types.ts`): `searchStencils`, `getStencil`,
`createStencil`, `saveStencil` and `markStencilReady`, plus the document's `catalogKey`. The app's
implementation is `apps/epistola/src/main/resources/static/js/editor-boot.js`; `saveStencil` sends
`clearParameterSchema` when the author removed every parameter.

### Usage

`GetStencilUsagePage` lists the template variants that use a stencil, and for each whether its
working model still fits the stencil as it is now. A variant that no longer fits is fixed in the
template.

### When a template no longer fits

Because a stencil can change after a template was written, the fit is checked where it matters:

- **Marking a variant ready** (`MarkVariantReady`) refuses an instance that does not fit the
  stencil's working copy; **marking a stencil ready** (`MarkStencilReady`) checks the stencil's own
  instances;
- **Releasing a catalog** runs the same check over every template variant and every stencil's own
  content (`catalog/ReleaseStencilCheck.kt`). A stencil of the released catalog comes from the
  content being released; a stencil of another catalog from that catalog's latest release that kept
  its content.

An instance does not fit when it:

- fills a placeholder the stencil does not have;
- leaves a required parameter unbound (no binding and no default);
- binds a parameter the stencil no longer declares while leaving one it declares unbound: most
  likely a renamed parameter, which would otherwise render empty without a word;
- binds a value whose type is certain to a parameter declared with another type: a literal, or a
  path the template's data contract types (`customer.name` → string). A function, an operator, a
  loop item or a field the contract does not describe is not judged;
- names a stencil that does not exist;
- makes a stencil contain itself (recursion);
- nests stencils deeper than 5.

A binding for a parameter the stencil does not declare is otherwise harmless: it is ignored when
rendering and does not block. Saving **Configure parameters…** in the editor drops it. Saves do not run this check, and a preview renders an instance that does not
fit as far as it can, so a template stays usable while its author fixes it.

### Nesting

A stencil's content may contain stencil instances. A reference without a `catalogKey` means the
catalog of the document it is written in: the template's catalog, or, inside a stencil, that
stencil's catalog. Recursion and depth are judged lexically: a template's fill may hold an instance
of the very stencil it fills, because that fill is the template's content, not the stencil's; inside
a stencil's own content, a fill holding the stencil that owns it is still recursion.

### Scope

Expressions see what is in scope where they are **written**, not where they render:

| Content                                                  | Sees                                                                                                                              |
| -------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| A stencil's own content, including a placeholder default | its parameters (`params`, or the instance's `paramsAlias`) and `sys`; plus the template's data if it declares `readsTemplateData` |
| A fill written in a template                             | the template's data and loop variables                                                                                            |
| A fill written in a stencil                              | that stencil's scope                                                                                                              |

A placeholder can also pass the stencil's parameters to its fill (`passParameters`); see
[stencil-placeholders.md](stencil-placeholders.md#4-passing-parameters-to-a-fill). Styles, fonts and
page state still follow where content renders, not where it was written.

By default a stencil is **isolated**: its content sees only its parameters and `sys`, so everything it
needs comes in through a parameter and it works in any template that binds them. A stencil that
declares `readsTemplateData: true` also sees what is in scope where the instance sits — the template's
data and, inside a template loop, its loop variables. Nested, that stays lexical: an inner stencil
sees the template's data only if the outer stencil reads it too.

## Architecture

### Data model

A stencil is a row in `stencils`: metadata, plus `content` (JSONB), `parameter_schema` (JSONB,
nullable) and `reads_template_data`, with tenant isolation (`tenant_key`) and its catalog
(`catalog_id`). Released stencils are held by the catalog's releases.

### Stored form of an instance

```jsonc
{
  "nodes": {
    "inst": {
      "id": "inst",
      "type": "stencil",
      "slots": ["inst:children"],
      "props": {
        "stencilKey": "letterhead",
        "catalogKey": "shared", // only when not the referencing document's own catalog
        "parameterBindings": { "date": "invoiceDate" },
        "paramsAlias": "params", // optional
      },
    },
    "inst:fill:body": {
      "id": "inst:fill:body",
      "type": "placeholder",
      "slots": ["inst:fill:body:fill"],
      "props": { "name": "body" }, // may carry parametersAs
    },
    // ...the fill's own content nodes, with the IDs the template gave them
  },
  "slots": {
    "inst:children": {
      "id": "inst:children",
      "nodeId": "inst",
      "name": "children",
      "children": ["inst:fill:body"],
    },
    "inst:fill:body:fill": {
      "id": "inst:fill:body:fill",
      "nodeId": "inst:fill:body",
      "name": "fill",
      "children": ["..."],
    },
  },
}
```

- The single `children` slot (`{inst}:children`) holds one `placeholder` **carrier** per non-empty
  fill, with id `{inst}:fill:{name}` and a `fill` slot `{inst}:fill:{name}:fill`. A placeholder the
  template does not fill is absent.
- The IDs are derived from the instance and the placeholder name, so storing an unchanged instance
  always produces the same document.
- No `version`, `draftVersion`, `parameterSchemaSnapshot` or `readsTemplateData` is stored. Before
  2.0 the stencil was named by `stencilId`; it is now `stencilKey`.

### Collapse and expand

`stencils/model/StencilInstances.kt` converts between the two forms:

- **`collapse`** — every template write (`TemplateDocumentPreparation`) stores instances in the form
  above, whatever arrives: a document with the stencil's content inlined, as the editor and MCP read
  it, is collapsed to references and fills before it is validated and stored. Idempotent.
- **`expand`** — inlines each stencil's content below its instance, with IDs derived from the
  instance and the stencil's own (`{inst}:{nodeId}`), and puts each fill into the stencil's
  placeholder of the same name. Expanding the same document twice gives the same IDs; fill content
  keeps its own IDs. It also reports every way the document no longer fits (the findings behind
  marking ready and releasing).

Expanding sets two transient props on the instance, `parameterSchemaSnapshot` and
`readsTemplateData`, read by validation and rendering; collapse drops them again.

Where the stencil comes from:

- the editor context (`GetEditorContext`, also used by quality checks and MCP reads), and previews
  expand from the working copies (`StencilResolvers`);
- a release render expands from the stencils held by that release; a stencil of another catalog
  comes from the release of that catalog the request's environment deploys, else its latest release
  that kept its content (`generation/release/ReleaseRenderSource.kt`).

### Rendering

`StencilNodeRenderer` (in `modules/generation`) renders an expanded instance as a container. It
guards against recursion and nesting deeper than the limit, evaluates the bindings in the scope the
instance sits in, and gives the content its parameters (and `sys`, and the template's data only when
the stencil reads it). `PlaceholderNodeRenderer` renders a fill in the scope recorded where the
instance sits, and a default in the stencil's.

## Upgrade from 1.x

The 2.0 upgrade converts stored data without changing what renders, except where noted:

- `V20261018200400__core_stencil_working_copy.sql` — each stencil's working copy is its draft where it
  had one, else its latest published version.
- `V20261018200500__core_template_content_v8.kt` — every stored template version is rewritten to the
  reference form:
  - an instance whose copy matches the stencil version it pinned (apart from fills) becomes a
    reference plus its fills; pinned to an older version, it now renders the current stencil, and is
    reported;
  - an instance with no `stencilId`, whose stencil or pinned version is gone, or whose copy diverged
    from that version, becomes plain content in its place, and is reported;
  - a fill that read the instance's parameters makes the stencil's placeholder pass them
    (`passParameters`), keeping a non-default alias as the fill's `parametersAs`;
  - a stencil whose content reads anything but its parameters and `sys` gets `readsTemplateData`.

What is reported is logged and stored in `app_metadata` under the key
`upgrade-2.0-template-content`.
