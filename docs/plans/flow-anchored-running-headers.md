# Page headers and footers anywhere in the flow

> **Status:** Design, implemented on branch `feat/flow-anchored-page-bands` (not yet merged) and
> epistola-contract PR #93. Tracks [#1020](https://github.com/epistola-app/epistola-suite/issues/1020);
> the decision is [ADR 0027](../adr/0027-page-headers-and-footers-by-section.md). This replaces an
> earlier draft of the same name that covered headers only; git history keeps it. Where the
> implementation settled a detail differently, it says so below.

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

## The model: headers look down, footers look up

**A document is divided into sections by its page breaks.** The division uses the rendered flow,
so a page break inside a false conditional does not divide the document, and one inside a loop
divides it once per iteration. Each section starts on a new page.

A block is **at the start of its section** when no content comes before it in that section.
Other headers and footers, and wrappers that have not drawn anything yet (such as the container a
stencil opens with), do not count as content.

### Headers apply to what comes after them

1. **At the start of a section** (the document start, or right after a page break), a header
   applies from that page.
2. **Several headers at the start of a section** form a first-page variant: the first applies to
   the section's first page, the next to its second page, and the last one continues from there.
   This is today's "first page plus running header", now available per section.
3. **After content**, a header takes over from the page after the one it lands on. If several land
   on the same page, they take over one page at a time, in flow order.
4. **A header lasts** until the next header takes over. An empty header still reserves its
   `height`; set `height` to `0` to switch the band off (as existing documents with an empty header
   reserve it today).
5. **Pages before the first header** get no header.

### Footers apply to what comes before them

1. **A footer covers its own section**, wherever in that section it sits. The natural place is at
   the bottom.
2. **It also covers every section above it that has no footer of its own**, back to the previous
   footer. Such sections get its running (last) footer.
3. **Several footers in one section** form a first-page variant: the first applies to the section's
   first page, the next to its second page, and the last one to the rest. Where they sit does not
   matter, only their order. A one-page section therefore never shows the second footer.
4. **Pages after the last footer** get no footer. This mirrors pages before the first header.

`hideOnFirstPage` keeps its meaning: page 1 of the document.

### Labels and a warning, so the result is not a surprise

- **The editor labels every header and footer** with what it gets, computed from the page breaks in
  the document (a conditional shows "depends on data"). Examples: "Header · from this page",
  "Header · from the next page", "Footer · first page of section", "Footer · pages 2+ of section",
  "Footer · also covers the sections above".
- **The validator warns** (`WARNING`, which the portable validator already supports; nothing emits
  one yet) when a section holds more than one footer and they are not directly next to each other
  in the flow. Adjacent footers read as a deliberate first-page variant. Scattered ones are usually
  an accident, typically a letter-shell stencil with a footer plus a footer in the template, where
  the second one silently disappears on a one-page letter. Headers need no such warning, because a
  header's position is meaningful.

### Why footers cannot switch in the middle of a section

A page's bottom band is reserved before the page is laid out. The footer that should cover page N
is the next one _below_ page N's content, which has not been laid out at that point. Switching
mid-section would need either a second layout pass, which may not settle because footer heights
move content, or deferred painting (see _Deferred_). Page breaks cover the real cases: cover,
letter, terms and appendix, and one letter per loop iteration.

Headers do not have this problem. The header that takes over on page N landed on an earlier page,
and the spike shows every earlier landing is known when page N's band is chosen.

### Existing documents keep their meaning

Every document the current validator accepts renders the same under this model, with no migration:

| Existing document                       | Today                     | New model                                                        |
| --------------------------------------- | ------------------------- | ---------------------------------------------------------------- |
| One header at the top                   | every page                | a header at the start of section 0, lasting until replaced       |
| Two headers at the top                  | page 1: H1; pages 2–N: H2 | a first-page variant at the document start; H2 runs to the end   |
| One footer at the end, with page breaks | every page                | it covers its own section and every footer-less section above it |
| Footer with `hideOnFirstPage`           | hidden on page 1          | unchanged                                                        |

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
- **Header landings are known in time.** A zero-height anchor records its page in `draw()`. Every
  anchor drawn on an earlier page is known when page N's band is chosen, and none from page N
  itself. `layout()` would be wrong: iText may lay an anchor out on one page and then move it.
- **Anchors are invisible.** They do not move body text and, with `role = null`, add nothing to a
  tagged PDF.
- **Painting matches the reservation.** The `END_PAGE` handlers read the decision the margins
  function recorded for that page, so the reserved band and the painted header cannot disagree.

### Steps

1. **Emit.** In the body render only (not band content, not measurement):
   - `PageBreakNodeRenderer` emits a `SectionPageBreak`;
   - `PageHeaderNodeRenderer` and `PageFooterNodeRenderer` emit a zero-height anchor carrying a
     _band occurrence_ `(nodeId, ordinal, contextSnapshot)`. The snapshot captures the loop and
     parameter scope, because in a loop the same node ID renders once per iteration with different
     data.
2. **Classify.** Sections and "at the start of its section" are decided while the body renders,
   which happens in document order: a page break starts a section, and `NodeRendererRegistry`
   marks content after any node that draws something (containers, stencils, conditionals, loops,
   tables, columns, the address block and the bands themselves do not count). Conditionals and
   loops are evaluated at that point, so this is exact rather than conservative. _(Implementation
   note: this replaced a separate walk over the built element tree.)_
3. **Measure.** Each occurrence's band height is `max(height prop, measured content)`, exactly as
   ADR 0008 does today, but measured per occurrence with its own context snapshot. This reuses
   `buildBandWrapper` and `measureBandContentHeight`, and replaces `measureEffectiveBandHeights`.
4. **Schedule.** `PageBandSchedule` is pure and unit-tested:
   - **Footers** are static. From the occurrences per section it derives each section's footer
     list, using rules 1–4 above.
   - **Headers** are a queue of eligible headers, one promoted per page. Section-start headers
     become eligible on their section's first page, replacing anything still queued. The others
     become eligible on the page after they land.

   The margins function tracks which section the current page is in, from the pending
   `SectionPageBreak` or else the previous page's section, and asks the schedule for the page.

5. **Reserve.** The margins function returns top = the header occurrence's `marginTop` + band,
   bottom = the footer's `marginBottom` + band, sides as today. It records the decision per page.
6. **Paint.** `PageHeaderEventHandler` and `PageFooterEventHandler` take the recorded occurrence
   for the page instead of a static node ID. Their drawing code is unchanged, except that the band
   is built with the occurrence's context snapshot plus the page parameters.
7. **Address block.** `bodyContentTopPt` becomes the page-1 header band, which is known before
   layout.
8. **Delete** `pageHeaderNodesInDocumentOrder`, `HeaderBands` and `computeHeaderBands`, the
   first-page spacer, and the footer `firstOrNull`.

Pass count is unchanged: one pass, or two when `sys.pages.total` is used. The per-render state is
recreated for each pass. `TwoPassAnalyzer` already walks header and footer descendants wherever they
sit.

## Compatibility (stability contract)

- **Published versions.** Add `RenderingDefaults.V4` with `sectionPageBands = true` and make it
  `CURRENT`. Versions published under V1–V3 keep the old code path, frozen. Separately, a parity
  test proves the new path lays out legacy-shaped documents identically (body baselines and band
  positions per page), because a published version with no resolved theme renders with `CURRENT`.
- **No data migration.** Existing documents keep their meaning (see the table above), so stored
  drafts and imported catalogs need no rewriting.
- **Contract (epistola-contract 1.4.0).**
  - Drop the three `PAGEHEADER_*` rules (the constants stay, deprecated). Add `PAGEBAND_NESTED`
    (error: a header or footer inside another) and `PAGEFOOTER_NOT_ADJACENT` (warning: a section's
    footers are not adjacent children of one slot). Done on `feat/page-bands-anywhere` in
    epistola-contract.
  - Remove `maxInstancesPerDocument` from both and rewrite the example descriptions. Registry
    examples are single-component fragments, so the letter shell is demonstrated in the demo
    catalog rather than as an example.
  - **No catalog `schemaVersion` bump.** The contract bumps it only for changes that are not
    round-trip compatible, and this one relaxes the rules: every v7 archive stays valid and the
    JSON shape is identical. The accepted cost is that an older Suite importing a catalog with
    several footers renders its first footer on every page; the release notes say catalogs using
    several footers need this Suite version or later. Exchange validates with the same contract,
    so it takes 1.4.0 too.
- **Suite validation codes.** Keep `PAGEHEADER_TOO_MANY`, `_ROOT_MISSING` and `_NOT_AT_ROOT` in
  `ValidationCode`, deprecated and no longer emitted, because REST clients may match on them.
  Check how the Suite surfaces a `WARNING` finding, since none is emitted today.

## Editor

- **Remove the header and footer rules** from `commands.ts` (insert, move,
  `validateRootContentBoundaries`), `drop-logic.ts`, `EpistolaPalette._insertNode`,
  `_getRootInsertBounds` (which removes a latent bug with two headers) and the canvas/tree
  `isFixedPageBlock`. Headers and footers become ordinary blocks.
- **One new rule:** no header or footer inside a header or footer. It is checked for every
  restored subtree too (paste, stencil content), not only the inserted node's type.
- **Labels and the warning**, as described in the model above.
- **Stencils need no special work.** Their content is copied into the template and re-keyed, so a
  letter shell's header and footer become ordinary blocks in the template's flow.

## Surfaces

- **REST:** only the contract bump.
- **MCP:** the component types come from the contract registry. Update
  `ComponentTypesIntegrationTest` (it currently asserts 2 and 1) and the stale `ComponentTypeInfo`
  KDoc.
- **Demo catalog** (`apps/epistola-demo/.../catalogs/demo`, mirrored in the test fixture catalog):
  - `letter-shell` gains a header at its top and a footer at its bottom;
  - a sectioned document: a cover, a letter, terms with their own header and footer, and a
    mid-document header switch;
  - `release.version` 5.18.3 → 5.19.0, fingerprint regenerated.
- **Docs:**
  - a new ADR (next free number, 0027) for this model, recording the decisions on #1020's open
    questions;
  - rewrite the header and footer sections of `docs/generation.md` and `docs/editor-features.md`;
  - remove the stale `PageHeaderCardinalityValidator` references (`generation.md`,
    `editor-features.md`, `output-formats.md`, the `DirectPdfRenderer` KDoc);
  - one changelog fragment.

## Open questions from #1020, answered

| #   | Question           | Answer                                                                                         |
| --- | ------------------ | ---------------------------------------------------------------------------------------------- |
| 1   | Footers too?       | Yes, mirrored: footers cover what comes before them, scoped by section.                        |
| 2   | Last-page header   | Out of scope. It needs the page count per section, i.e. a second pass.                         |
| 3   | Odd/even           | Out of scope. It would be a property on the header, not a placement rule.                      |
| 4   | `height` prop      | Kept as a minimum (ADR 0008). Removing it would be a wire break for no gain.                   |
| 5   | Address block      | Positioned against the page-1 header, which is known before layout.                            |
| 6   | Loops / datatables | Allowed. Each rendered occurrence counts. A page break in the loop gives one section per item. |

## Delivery

1. **Contract PR** (epistola-contract → 1.4.0): validator, registry, fixtures, docs.
2. **Suite PR:**
   - `PageBandSchedule` and its unit tests;
   - the anchors, `SectionPageBreak`, the content marking, the margins function and the handlers;
   - the V4 gate and parity tests;
   - the contract bump, validation codes and MCP tests;
   - the editor;
   - demo catalog, docs and changelog.

### Tests

- **Unit:** `PageBandSchedule` covering the legacy table above, per-section first-page variants,
  a mid-section header switch, several switches landing on one page, a section-start header
  replacing queued switches, footers covering footer-less sections above, pages after the last
  footer, an empty header, and occurrences from a loop.
- **Generation, extending `PageHeaderFooterTest`:**
  - a letter-shell stencil with a header at its top and a footer at its bottom;
  - a footer at the end with page breaks;
  - a header switching mid-section;
  - a header inside a false conditional;
  - a page break plus letterhead inside a loop (a batch of letters);
  - a nested page break;
  - per-occurrence band height;
  - legacy parity;
  - the address block under a sectioned header.
- **Probes:** `PageBandItextProbeTest` pins the iText behaviour across upgrades.
- **Editor:** rewrite the header and footer specs in `engine.test.ts`, `drop-logic.test.ts` and
  `drop-handler.test.ts`. Add specs for the nested-band rule on paste and stencil content, and for
  the labels.
- **Contract:** validator fixtures (including the warning) and parity cases.

## Spike results (2026-09-29)

`modules/generation/src/test/kotlin/app/epistola/generation/pdf/PageBandItextProbeTest.kt`, plain
iText 9.7.1, all passing:

| Probe                                                           | Result                                                                                                                                              |
| --------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| Different top and bottom band per page via `setPageMargins(fn)` | Works; the function runs exactly once per page                                                                                                      |
| Page break announces its section before the new page's band     | Works, both top-level and nested in a `Div`                                                                                                         |
| `END_PAGE` handler paints what the margins function decided     | Works                                                                                                                                               |
| Zero-height anchor elements                                     | Do not move body text; with `role = null` they add no structure elements to a tagged PDF                                                            |
| Anchor landing pages (for the mid-section header switch)        | Recorded in `draw()`, every earlier-page landing is known when page N's band is chosen, and none from page N itself; also nested and in table cells |
| Why `draw()` and not `layout()`                                 | An anchor kept with the next block is laid out on page 1, discarded, and drawn on page 2. A keep-together block is not trial-laid at all            |
| Anchor at the foot of a full page                               | Lands on that page unless `keepWithNext`, in which case it moves with the next block                                                                |

## Deferred: footers switching in the middle of a section

If authors need footer switches without a page break, two changes together make the footer
covering page N "the first footer at or below page N":

1. **Reserve the tallest footer of the section** on every page of it, so the layout no longer
   depends on which footer is painted. A shorter footer then leaves a gap above it.
2. **Paint footers after layout finishes**, once every footer's landing page is known. That means
   keeping pages in memory (`immediateFlush = false`) instead of writing them out as they fill,
   which costs memory on large documents and batch runs.

It remains a single pass and adds to the footer rules without changing them.
