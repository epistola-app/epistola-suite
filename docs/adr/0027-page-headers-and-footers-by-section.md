# ADR 0027: Page headers and footers placed anywhere, by section

- **Status:** Accepted
- **Date:** 2026-09-29
- **Deciders:** Epistola team
- **Tags:** generation, rendering, pdf, headers, footers, editor, catalog

## Context

Until now the page header model was positional: at most two `pageheader` nodes, both direct
children of the root slot (the first for page 1, the second for pages 2–N), and one `pagefooter`,
which the editor pinned to the bottom of the document. The portable validator, the renderer and
the editor each enforced their share of that ([#399](https://github.com/epistola-app/epistola-suite/issues/399),
[#416](https://github.com/epistola-app/epistola-suite/issues/416)).

Authors need more ([#1020](https://github.com/epistola-app/epistola-suite/issues/1020)):

- **Letterheads in stencils.** A stencil that is the master layout of many letters should carry
  the letter's header and footer. Stencil content lands under the `stencil` node, not the root, so
  a header there failed validation and rendering.
- **Sectioned documents.** A cover, a letter, terms and an appendix, each with its own header and
  footer.
- **Conditional headers.** A header that applies only when a data-driven section renders.

Two constraints shape the answer. The template document is a GA wire format, so every document
the old model accepted must keep its meaning. And rendering happens in one forward pass through
iText, which reserves a page's top and bottom band when it creates the page, before the page's
content is laid out.

## Options considered

1. **Switch on the page after the block lands**, for headers and footers alike (the earlier plan).
   Simple and single-pass, but it breaks every existing footer: the editor always put the footer
   last, so it would take effect after the last page and never be drawn. It also switches one page
   late in the common case of a section that starts with a page break.
2. **Section-scoped for both.** Page breaks divide the flow into sections; a header or footer
   covers its whole section wherever it sits. Keeps existing documents, known before layout, but a
   header can never switch without a page break, and a footer at the end of a document with page
   breaks would only cover the last section unless a special backfill rule is added.
3. **Headers look down, footers look up** (chosen). Sections as in option 2, but headers apply to
   what comes after them (from their page at the start of a section, from the next page after
   content) and footers to what comes before them (their section and the footer-less sections
   above it).
4. **Footers that switch mid-section too.** Possible only by reserving the tallest footer of a
   section on every page and painting footers after layout finishes (`immediateFlush = false`),
   which costs memory on large documents and leaves a gap above shorter footers. Deferred.

## Decision

Option 3. Page breaks in the rendered flow (a false conditional's do not count, a loop's count
once per iteration) divide the document into sections.

- **Headers apply to what comes after them.** At the start of a section a header applies from
  that page; several in a row form a first-page variant. After content, a header takes over from
  the page after it lands; several landing on one page take over one page at a time. A header
  lasts until replaced; pages before the first header have none.
- **Footers apply to what comes before them.** A footer covers its own section, wherever in it,
  and the sections above it that have no footer, with its running (last) footer. Several in one
  section form a first-page variant in flow order. Pages after the last footer have none.
- **One structural rule remains:** no header or footer inside another (`PAGEBAND_NESTED`, an
  error). A section whose footers are not adjacent in one slot gets a warning
  (`PAGEFOOTER_NOT_ADJACENT`), since order rather than position then decides which page gets which.

The answers to #1020's open questions:

| Question           | Answer                                                                                       |
| ------------------ | -------------------------------------------------------------------------------------------- |
| Footers too?       | Yes, mirrored: footers cover what comes before them, scoped by section.                      |
| Last-page header   | Out of scope; it needs each section's page count, i.e. a second pass.                        |
| Odd/even headers   | Out of scope; that is a property of a header, not a placement rule.                          |
| The `height` prop  | Stays a minimum ([ADR 0008](0008-header-footer-height-minimum.md)). `0` switches a band off. |
| Address block      | Positioned against the header that applies on page 1.                                        |
| Loops / datatables | Allowed; every rendered occurrence counts, with its own data and its own measured height.    |

## Consequences

- **Existing documents keep their layout.** Two headers at the top are a first-page variant whose
  second header runs to the end; one footer at the end covers every section above it.
  `PageBandParityTest` proves identical text positions against the positional renderer. Versions
  published before this change also keep the positional code path itself: `RenderingDefaults` V4
  (`sectionPageBands = true`) selects the new model, V1–V3 the old.
- **Still one pass.** Each page's bands are chosen when iText creates the page, through the public
  `Document.setPageMargins { page -> … }`; a `SectionPageBreak` announces its section before the
  new page, and header anchors record their landing page in `draw()`. `PageBandItextProbeTest`
  pins that iText behaviour across upgrades.
- **No catalog wire change.** The rules only loosen, so `schemaVersion` stays 7 and every existing
  archive stays valid (epistola-contract 1.4.0). The accepted cost: an older Suite importing a
  catalog with several footers renders its first footer on every page. Exchange validates with the
  same contract and needs 1.4.0 to accept catalogs that use the new placements.
- **The deprecated validation codes stay.** `PAGEHEADER_TOO_MANY`, `PAGEHEADER_ROOT_MISSING` and
  `PAGEHEADER_NOT_AT_ROOT` are no longer emitted but remain in the contract and in `ValidationCode`,
  because clients may match on them.
- **Footers cannot switch mid-section**, and a section that starts on a new page only because the
  previous one ran full (no page break) sees a mid-section header one page late. Page breaks cover
  the real cases; option 4 remains available if authors need more.
- **The editor mirrors the rules statically** (`engine/page-bands.ts`): labels say what each band
  gets, counting every page break regardless of conditionals, and marking bands inside a
  conditional or loop as depending on data.

The plan, spike results and delivery notes are in
[`plans/flow-anchored-running-headers.md`](../plans/flow-anchored-running-headers.md).
