# ADR 0045: Locations in a template, and highlighting them

- **Status:** Proposed
- **Date:** 2026-10-31
- **Deciders:** Epistola team
- **Tags:** contract, editor, preview, quality, component-registry
- **Related:** [ADR 0044](0044-quality-results-keyed-by-content.md), [ADR 0046](0046-quality-profiles-and-rules.md)

## Context

Several features need to point at a part of a template, and each has invented its own way or none:

- **Quality findings** point at whole editor nodes (`nodeIds`). A misspelling is three letters in one
  paragraph; a missing alt text is one field of an image; a wrong expression is the expression of a
  data list. The pilot of ADR 0044 keeps the flagged text as evidence beside a node id.
- **Previews** are to be rendered with partial data: what the caller did not supply is filled in from
  one of the template's examples and highlighted, and every input parameter can be highlighted so an
  author sees what changes per document and what does not.
- **The editor** shows markers for findings on the canvas, and will show the same highlights as the
  preview.
- **REST** may later return quality findings with a preview.

Each of these is the same question: which part of which template is this, so that the editor, the
renderer and a REST client can show it. A second question follows: which parts of a component are
prose, which are identifiers, which are expressions. The component registry lists a component's
fields (`inspector`: key and type), but `alt` (prose) and `itemAlias` (an identifier) are both
`text`, and a text block's rich content is not listed at all.

## Considered options

- **A. Node ids only.** What exists. Cannot point inside a block or at one field.
- **B. A suite-local type first, the contract later.** Faster now, but the editor (TypeScript, from
  the contract's packages) and the renderer would each need a copy until it moves.
- **C. A location type in the contract, alpha (chosen).** Defined once beside the template model it
  points into, generated for Kotlin and TypeScript.

## Decision

### 1. A location

A location names a part of a template, defined in `epistola-contract` beside the template model and
marked alpha:

| Part      | Meaning                                                                                                            |
| --------- | ------------------------------------------------------------------------------------------------------------------ |
| `subject` | the template variant or stencil, by identity                                                                       |
| `nodeId`  | the node                                                                                                           |
| `field`   | a JSON pointer into the node: `/props/content`, `/props/alt`, `/props/expression/raw`; absent: the node as a whole |
| `range`   | within rich text: the block (its path in the field's document) and character offsets in the block's text           |
| `quote`   | the text at the range, with a few characters before and after                                                      |

Offsets go stale as an author types, so they are a hint: a reader finds the range again by its quote
and the text around it (the W3C Web Annotation "text quote selector" approach), and treats a location
whose quote is gone as no longer there. A location can later carry a language for a part of a
document in another language than its variant; nothing uses that yet.

### 2. Field roles in the component registry

Every field a component declares gets a **role**:

| Role         | Meaning                                | Example                        |
| ------------ | -------------------------------------- | ------------------------------ |
| `prose`      | text a reader of the document reads    | a text block's content, `alt`  |
| `identifier` | a name used by the template, not shown | `itemAlias`, `indexAlias`      |
| `expression` | an expression evaluated against data   | a data list's `expression.raw` |
| `value`      | a setting: a number, a unit, a choice  | `width`, `listType`            |
| `reference`  | another resource                       | `imageKey`, a theme            |

A text block's rich content is declared as a `prose` field. The registry check
(`check-component-registry.mjs`) refuses a field without a role, so a new component is
described completely or not at all. Language checks extract every `prose` field (ADR 0044), so a new
component with prose is checked without a change to quality.

### 3. Highlights, in the editor and in previews

A **highlight** is a location with a kind, a style and a label: a quality finding (with its
severity), a value filled from example data, an input parameter. The editor offers one **highlights
port**: a plugin contributes a set of highlights, the editor draws them on the canvas, a node's
outline or a range's underline. Quality is one plugin feeding it; a preview of partial data is
another. The editor knows highlights, not quality.

A render can produce a **source map**: each rendered region with its location and its provenance
(literal template text; an expression's value, with the data path and whether the value was supplied,
taken from an example, or made up; an input parameter). A preview draws highlights from it. The shape
is fixed here; the renderer builds it when partial-data previews are built.

### 4. REST

A preview response may later carry findings and highlights, as locations. Nothing is added to REST
now; when it is, the contract's location type is what it returns.

## Consequences

- One definition of "where" for quality, the editor, the renderer and REST.
- Findings can point at a word, a field or a node, and survive edits around them.
- The component registry describes every field's role; that is a contract change, and the check makes
  it complete.
- Highlighting is an editor capability, so quality and preview do not know about each other.
- The pilot's findings move from node ids with a quote in their evidence to locations once the
  contract has the type.

## References

- [ADR 0044](0044-quality-results-keyed-by-content.md): what is checked and cached, per unit.
- [`docs/plugins.md`](../plugins.md): editor plugins and the host-provided ports they use.
- W3C Web Annotation Data Model, `TextQuoteSelector`.
