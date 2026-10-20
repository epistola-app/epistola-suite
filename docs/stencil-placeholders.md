# Stencil Placeholders

Decision records: [`adr/0001-stencil-placeholders.md`](./adr/0001-stencil-placeholders.md) (the
placeholder node) and contract
[ADR 0006](https://github.com/epistola-app/epistola-contract/blob/feature/v2/docs/adr/0006-stencil-instances-by-reference.md)
(instances by reference, lexical scope, passing parameters). How stencils are stored and expanded is
in [stencils.md](stencils.md).

## 1. Summary

A `placeholder` node marks a spot in a stencil's content that a using template fills with its own
block content. The stencil author gives it a name and, optionally, default content. A template that
uses the stencil stores only its fills — one per placeholder it fills — and the stencil's
placeholders receive them by name when the instance is expanded.

Placeholders carry block content; [parameters](stencil-parameters.md) carry data values. A stencil
can have both.

## 2. Document model

### 2.1 In a stencil's content

```jsonc
{
  "id": "<NodeId>",
  "type": "placeholder",
  "slots": ["<defaultSlotId>", "<fillSlotId>"],
  "props": {
    "name": "<kebab-case slug>", // required; unique within the stencil
    "description": "<string, optional>",
    "passParameters": false, // optional; see §4
    "parametersAs": "<name, optional>", // optional; the author's default name, see §4
  },
  "styles": {/* container-style props, optional */},
}
```

The placeholder owns two slots, dispatched on slot **name**, not position:

- **`default`** — the stencil author's default content, shown when the template does not fill the
  placeholder.
- **`fill`** — empty in the stencil's own content. When an instance is expanded, the template's fill
  is placed here.

### 2.2 In a template: the fill carrier

A template stores a placeholder only as a **fill carrier** inside a `stencil` node's `children`
slot, and only for a placeholder it fills with non-empty content:

```jsonc
{
  "id": "{inst}:fill:{name}",
  "type": "placeholder",
  "slots": ["{inst}:fill:{name}:fill"],
  "props": {
    "name": "{name}",
    "parametersAs": "<name, optional>", // the using side's choice, see §4
  },
}
```

It has a `fill` slot and no `default` slot. Its IDs are derived from the instance and the name, so
storing an unchanged template gives the same document. Clearing a fill removes the carrier; the
stencil's default shows again. Every template write collapses to this form
(`StencilInstances.collapse`), including a document that arrives with the stencil expanded.

### 2.3 Rendering

`PlaceholderNodeRenderer` renders the `fill` slot when it has children, otherwise the `default`
slot. The two are independent: a fill never changes the default.

### 2.4 Where placeholders are allowed

Validation of the document (`TemplateDocumentValidator`, delegating to the portable catalog
validator) enforces:

| Code                            | Rule                                                                    |
| ------------------------------- | ----------------------------------------------------------------------- |
| `PLACEHOLDER_NAME_INVALID`      | `name` must be a kebab-case slug.                                       |
| `PLACEHOLDER_NAME_DUPLICATE`    | Names are unique within a stencil (in a template: within one instance). |
| `PLACEHOLDER_NESTED_DEFINITION` | In a stencil's content, a placeholder may not sit inside another one.   |
| `PLACEHOLDER_OUTSIDE_STENCIL`   | In a template, a placeholder must be a descendant of a `stencil` node.  |

Across stencils, names are independent. Renaming a placeholder is remove + add: existing fills keep
the old name and no longer fit (§5).

## 3. Scope of a fill

Expressions are scoped **lexically**: a fill is written outside the stencil that owns the
placeholder, so it reads the scope where the instance sits, not the stencil's.

- A fill written in a template sees the template's data and loop variables (and, when the instance
  sits inside another stencil's fill, whatever that place sees).
- A fill written in a stencil (filling an inner stencil's placeholder) sees that stencil's scope.
- The placeholder's **default** is the stencil's own content and sees the stencil's `params` (or its
  instance's `paramsAlias`).

Only expressions follow this rule; styles, fonts and page state come from where the fill renders
inside the stencil.

The renderer implements it by recording the scope an instance is entered from (`fillScope` on the
render context, set by `StencilNodeRenderer`) and rendering a fill in that scope.

## 4. Passing parameters to a fill

A placeholder in a stencil may set `passParameters: true`. Its fill then also sees the stencil's
parameters, as evaluated at the instance, under a name resolved in this order:

1. `parametersAs` on the template's fill carrier — the using side's choice wins;
2. `parametersAs` on the stencil's placeholder — the author's default;
3. `params`.

`parametersAs` follows the `paramsAlias` rules: it matches `^[a-z][a-zA-Z0-9_]{0,63}$` and may not be
a reserved scope name — `sys`, `item`, `index` (`NODE_PARAMS_ALIAS_RESERVED`). The passed name is added next to the fill's
lexical scope, so it hides a template data field of the same name.

While expanded, the using side's name is carried on the stencil's placeholder as `fillParametersAs`;
collapse stores it back as `parametersAs` on the carrier.

## 5. Fit and the release check

Because a template's fills are matched to the stencil as it is now, a template can end up filling a
placeholder the stencil no longer has. Expanding reports it (`UNKNOWN_PLACEHOLDER`) and the fill is
not rendered; marking the variant ready and releasing a catalog refuse it. See
[stencils.md](stencils.md#when-a-template-no-longer-fits).

## 6. Nesting and recursion

Stencils may contain stencils. An outer stencil can fill an inner stencil's placeholders, and can
offer one of its own placeholders inside such a fill, which the template then fills. When an
instance is expanded, each fill goes to the outermost placeholder of its name; an inner stencil's
placeholders belong to the outer stencil to fill.

Recursion is judged lexically too: a template's fill may contain an instance of the very stencil it
fills, because the fill is the template's content. Inside a stencil's own content, a fill holding
the stencil that owns it is recursion (`STENCIL_RECURSION`). Nesting deeper than 5 is refused
(`STENCIL_NESTING_DEPTH_EXCEEDED`).

| Layer     | What it does                                                                    | When it runs                        |
| --------- | ------------------------------------------------------------------------------- | ----------------------------------- |
| Editor    | Refuses an insert or move that would recurse or nest too deep, lexically        | Picker, drag and drop               |
| Validator | Walks the stored document for recursion and depth                               | Every template and stencil write    |
| Expansion | Stops at a stencil already being inlined, or at the depth limit, and reports it | Editor context, ready mark, release |
| Renderer  | Ancestor stencil set and depth counter; aborts with a diagnostic                | Every render                        |

## 7. Upgrade from 1.x

1.x templates held a copy of the stencil with every placeholder, its default and its fill. The 2.0
upgrade keeps only the non-empty fills, as carriers. A fill that read the instance's parameters
makes the stencil's placeholder `passParameters: true`, and a non-default instance alias becomes the
carrier's `parametersAs`, so the fill sees what it saw before. See
[stencils.md](stencils.md#upgrade-from-1x).

## 8. Open questions

- **Inline placeholders in rich text** — block-level only for now.
- **Required placeholders** — every placeholder is optional; an unfilled one shows its default.
- **Repeating fills** — out of scope; the data-binding system (`datatable`, loops) handles
  repetition.
- **Passing values other than parameters** — a placeholder cannot pass, say, the current item of a
  loop inside the stencil to its fill; it can be added to `passParameters` later.
