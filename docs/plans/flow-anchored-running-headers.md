# Page headers and footers anywhere in the flow

> **Status:** Design, not implemented. Tracks
> [#1020](https://github.com/epistola-app/epistola-suite/issues/1020). This replaces an earlier
> draft of the same name that covered headers only and assumed a custom `DocumentRenderer`; git
> history keeps it. The iText mechanics were proven by a spike, recorded below.

## Goal

Authors can place any number of `pageheader` and `pagefooter` blocks anywhere a block can go:
inside stencils (a letterhead stencil carrying the letter's header and footer), conditionals and
loops. Where a header or footer sits decides which pages it applies to.

Today the model is positional:

- at most two headers, both direct children of the root slot (first = page 1, second = pages 2–N);
- one footer, pinned by the editor to the bottom of the root slot.

Four layers enforce this:

| Layer                                                         | Rule                                                                                                         |
| ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Portable `TemplateValidator` (`epistola-catalog`, contract)   | `PAGEHEADER_TOO_MANY`, `PAGEHEADER_NOT_AT_ROOT`, `PAGEHEADER_ROOT_MISSING`; no footer rule at all            |
| Contract component registry                                   | `maxInstancesPerDocument`: header 2, footer 1 (the editor takes these from the contract at runtime)          |
| `DirectPdfRenderer.pageHeaderNodesInDocumentOrder`            | throws on a nested header or more than two; the footer is the first `pagefooter` in the node map             |
| Editor `commands.ts`, `drop-logic.ts`, palette, editor bounds | headers only at the top of the root slot, the footer only at the bottom; pasted or stencil content unchecked |

Because the validator also runs on stencils, a header at a stencil's own root passes on its own and
then fails once the stencil is embedded in a template.

## The model: sections

**A document is divided into sections by its page breaks.** The division uses the rendered flow,
so a page break inside a false conditional does not divide the document, and one inside a loop
divides it once per iteration. Each section starts on a new page.

Within a section, header and footer blocks are collected in flow order. Their exact position in
the section does not matter, only their order.

1. **One header in a section** applies to every page of that section.
2. **Several headers in a section:** the first applies to the section's first page, the second to
   its second page, and so on. The last one applies to the rest of the section. Two headers are
   therefore "first page plus running header", now available per section.
3. **A section with no header** continues the running header of the section before it, meaning
   the last header that section used. An empty `pageheader` switches the header off.
4. **Pages before the first header** get no header.

Footers follow the same four rules, except for rule 4:

4. **Pages before the first footer** use the first footer.

Rule 4 differs for footers because of compatibility. For years the editor forced the footer to be
the last block of the document, so in an existing document with page breaks the footer is in the
_last_ section. Without the backfill, every page before that section would lose its footer. The
cost is that "a footer only on the appendix" also puts that footer on the pages before it, unless
they have a footer of their own (an empty one will do).

`hideOnFirstPage` keeps its meaning: page 1 of the document.

### Why sections, and not "switch on the page after the block lands"

The earlier draft let a header placed anywhere take effect on the page after the one it landed on.
That breaks existing footers: a footer at the end of the document would take effect after the last
page, so it would never be drawn. It also switches one page late in the common case, a section that
starts with a page break.

Sections avoid both problems. Which header or footer each section uses is known _before_ layout,
because the element tree is fully built (conditionals and loops evaluated) before anything is added
to the iText `Document`. Layout only has to report which section a new page starts. What remains is
a pure function that can be unit-tested.

**Not supported:** a switch in the middle of a section, i.e. a new header for a chapter that starts
mid-page with no page break. The spike shows this is feasible later (see _Deferred_ below), and it
can be added without changing rules 1–4.

### Existing documents keep their meaning

Every document the current validator accepts renders the same under the section model:

| Existing document                       | Today                     | Section model                                                     |
| --------------------------------------- | ------------------------- | ----------------------------------------------------------------- |
| One header at the top                   | every page                | section 0 has one header, and later sections inherit it           |
| Two headers at the top                  | page 1: H1; pages 2–N: H2 | section 0: H1 then H2; later sections inherit H2, the running one |
| One footer at the end, with page breaks | every page                | it is the first footer, so rule 4 backfills every earlier section |
| Footer with `hideOnFirstPage`           | hidden on page 1          | unchanged                                                         |

Parity tests prove this against the #399 documents and the demo catalog. The behaviour also stays
gated for published versions (see Compatibility).

## Rendering design (single pass)

These iText 9.7.1 mechanisms are proven by `PageBandItextProbeTest`:

- **Per-page bands through a public API.** `Document.setPageMargins(Function<Integer, PageMarginBoxes>)`
  is consulted when a page's layout area is created. A small `PageMarginBoxes` subclass returns the
  margin sizes we computed, instead of measuring margin-box content. iText caches the result per
  page, so the function runs once per page. No `DocumentRenderer` subclass and no first-page spacer
  are needed.
- **Section starts are known before the page is laid out.** An `AreaBreak` subclass whose renderer
  announces its section in `layout()` runs before the new page's margins are chosen. This also holds
  when the break is nested in a container `Div`, which is how `ContainerNodeRenderer` and stencils
  emit it.
- **Painting matches the reservation.** The `END_PAGE` handlers read the decision the margins
  function recorded for that page, so the reserved band and the painted header cannot disagree.

### Steps

1. **Collect.** A per-render `PageBandCollector` goes in the `RenderContext` of the body render only.
   It is not used when rendering band content or measuring.
   - `PageBreakNodeRenderer` emits a `SectionPageBreak(sectionIndex)` and advances the collector's
     section counter.
   - `PageHeaderNodeRenderer` and `PageFooterNodeRenderer` still emit nothing. They register a
     _band occurrence_ `(nodeId, sectionIndex, ordinal, contextSnapshot)`, capturing the loop and
     parameter scope, because in a loop the same node ID renders once per iteration with different
     data.
2. **Measure.** Each occurrence's band height is `max(height prop, measured content)`, exactly as
   ADR 0008 does today, but measured per occurrence with its own context snapshot. This reuses
   `buildBandWrapper` and `measureBandContentHeight`, and replaces `measureEffectiveBandHeights`.
3. **Schedule.** `PageBandSchedule` is a pure function: given the occurrences grouped by section,
   plus `(sectionIndex, pageInSection)`, it returns the header and footer occurrences for that page.
   The margins function tracks which section the current page is in, from the pending
   `SectionPageBreak` or else the previous page's section, and calls it.
4. **Reserve.** The margins function returns top = the header occurrence's `marginTop` + band,
   bottom = the footer's `marginBottom` + band, sides as today. It records the decision per page.
5. **Paint.** `PageHeaderEventHandler` and `PageFooterEventHandler` take the recorded occurrence
   for the page instead of a static node ID. Their drawing code is unchanged, except that the band
   is built with the occurrence's context snapshot plus the page parameters.
6. **Address block.** `bodyContentTopPt` becomes the page-1 header band from the schedule, which is
   known statically.
7. **Delete** `pageHeaderNodesInDocumentOrder`, `HeaderBands` and `computeHeaderBands`, the
   first-page spacer, and the footer `firstOrNull`.

Pass count is unchanged: one pass, or two when `sys.pages.total` is used. A fresh collector is made
per pass. `TwoPassAnalyzer` already walks header and footer descendants wherever they sit.

## Compatibility (stability contract)

- **Published versions.** Add `RenderingDefaults.V4` with `sectionPageBands = true` and make it
  `CURRENT`. Versions published under V1–V3 keep the old code path, frozen. Separately, a parity
  test proves the new path lays out legacy-shaped documents identically (body baselines and band
  positions per page), because a published version with no resolved theme renders with `CURRENT`.
- **Contract (epistola-contract 1.4.0).**
  - Drop the three `PAGEHEADER_*` rules. Add one: no header or footer inside a header or footer.
  - Set `maxInstancesPerDocument` to `null` for both. Rewrite the registry descriptions and
    examples: a per-section first-page variant, and a letterhead stencil.
  - Bump the catalog `schemaVersion` from 7 to 8 with a no-op migration. Otherwise an older Suite
    would import a catalog with several footers (it has no footer rule) and silently render the
    wrong one. With the bump it refuses with `CATALOG_SCHEMA_TOO_NEW`.
- **Suite validation codes.** Keep `PAGEHEADER_TOO_MANY`, `_ROOT_MISSING` and `_NOT_AT_ROOT` in
  `ValidationCode`, deprecated and no longer emitted, because REST clients may match on them.

## Editor

- **Remove the header and footer rules** from `commands.ts` (insert, move,
  `validateRootContentBoundaries`), `drop-logic.ts`, `EpistolaPalette._insertNode`,
  `_getRootInsertBounds` (which removes a latent bug with two headers) and the canvas/tree
  `isFixedPageBlock`. Header and footer become ordinary blocks.
- **One new rule:** no header or footer inside a header or footer. It is checked for every
  restored subtree too (paste, stencil content), not only the inserted node's type.
- **Chrome.** Each header and footer is labelled with what the section model gives it, for example
  "Header · section 2, first page" or "Footer · all pages". This is computed from the root slot's
  page breaks. Conditionals are shown as "depends on data".
- **Stencils need no special work.** Their content is copied into the template and re-keyed, so a
  letterhead stencil's header becomes an ordinary header in the template's flow.

## Surfaces

- **REST:** only the contract bump.
- **MCP:** the component types come from the contract registry. Update
  `ComponentTypesIntegrationTest` (it currently asserts 2 and 1) and the stale `ComponentTypeInfo`
  KDoc.
- **Demo catalog** (`apps/epistola-demo/.../catalogs/demo`, mirrored in the test fixture catalog):
  - `letter-shell` gains a letterhead header and footer;
  - a sectioned document (cover, letter, terms with their own header and footer, appendix);
  - `release.version` 5.18.3 → 5.19.0, fingerprint regenerated.
- **Docs:**
  - a new ADR (next free number, 0027) for the section model, recording the decisions on #1020's
    open questions;
  - rewrite the header and footer sections of `docs/generation.md` and `docs/editor-features.md`;
  - remove the stale `PageHeaderCardinalityValidator` references (`generation.md`,
    `editor-features.md`, `output-formats.md`, the `DirectPdfRenderer` KDoc);
  - one changelog fragment.

## Open questions from #1020, answered

| #   | Question           | Answer                                                                       |
| --- | ------------------ | ---------------------------------------------------------------------------- |
| 1   | Footers too?       | Yes, the same section model, plus the backfill rule for compatibility.       |
| 2   | Last-page header   | Out of scope. It needs the page count per section, i.e. a second pass.       |
| 3   | Odd/even           | Out of scope. It would be a property on the header, not a placement rule.    |
| 4   | `height` prop      | Kept as a minimum (ADR 0008). Removing it would be a wire break for no gain. |
| 5   | Address block      | Positioned against the page-1 header from the schedule.                      |
| 6   | Loops / datatables | Allowed. Each rendered occurrence counts, in flow order, within its section. |

## Delivery

1. **Contract PR** (epistola-contract → 1.4.0): validator, registry, schema 8, fixtures.
2. **Suite PR:**
   - `PageBandSchedule` and its unit tests;
   - the collector, `SectionPageBreak`, the margins function and the handlers;
   - the V4 gate and parity tests;
   - the contract bump, validation codes and MCP tests;
   - the editor;
   - demo catalog, docs and changelog.

### Tests

- **Unit:** `PageBandSchedule` covering the legacy table above, per-section first-page variants,
  inheritance, the footer backfill, an empty header, and occurrences from a loop.
- **Generation, extending `PageHeaderFooterTest`:**
  - a header inside a stencil;
  - a footer at the end with page breaks;
  - a header inside a false conditional;
  - a page break plus letterhead inside a loop (a batch of letters);
  - a nested page break;
  - per-occurrence band height;
  - legacy parity;
  - the address block under a sectioned header.
- **Probes:** `PageBandItextProbeTest` pins the iText behaviour across upgrades.
- **Editor:** rewrite the header and footer specs in `engine.test.ts`, `drop-logic.test.ts` and
  `drop-handler.test.ts`. Add a spec for the nested-band rule on paste and stencil content.
- **Contract:** validator fixtures, and a test for the no-op schema 8 migration.

## Spike results (2026-09-29)

`modules/generation/src/test/kotlin/app/epistola/generation/pdf/PageBandItextProbeTest.kt`, plain
iText 9.7.1, all passing:

| Probe                                                           | Result                                                                                                                                              |
| --------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| Different top and bottom band per page via `setPageMargins(fn)` | Works; the function runs exactly once per page                                                                                                      |
| Page break announces its section before the new page's band     | Works, both top-level and nested in a `Div`                                                                                                         |
| `END_PAGE` handler paints what the margins function decided     | Works                                                                                                                                               |
| Zero-height anchor elements                                     | Do not move body text; with `role = null` they add no structure elements to a tagged PDF                                                            |
| Anchor landing pages (for the deferred mid-section switch)      | Recorded in `draw()`, every earlier-page landing is known when page N's band is chosen, and none from page N itself; also nested and in table cells |
| Why `draw()` and not `layout()`                                 | An anchor kept with the next block is laid out on page 1, discarded, and drawn on page 2. A keep-together block is not trial-laid at all            |
| Anchor at the foot of a full page                               | Lands on that page unless `keepWithNext`, in which case it moves with the next block                                                                |

## Deferred: switching in the middle of a section

If authors need a new header for a chapter that starts mid-page, a header could also become a
zero-height anchor element. Its landing page, recorded in `draw()`, would make it active from the
next page. The probes show every input for that is available when page N's band is chosen, still
in a single pass. This adds to rules 1–4 and does not change them.
