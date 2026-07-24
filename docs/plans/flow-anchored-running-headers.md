<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# Flow-Anchored Running Headers — single-pass, dynamic height

## Context

The branch `feat/pageheader-first-page-variant` shipped a **positional ≤2-header** model
(≤2 `pageheader` nodes, both direct children of root; first → page 1, second → 2..N).
It is finished and tested and is **not touched** by this work.

This is a **separate follow-up change** (branch fresh from `main`) replacing that with a
**flow-anchored running header**: a `pageheader` node is a _flow anchor_ placeable
anywhere in content flow, unlimited count. It still paints in a reserved band at the
**top** of every governed page (drawn at iText `END_PAGE`); the anchor only selects
_which_ header fills the band, from its position onward. Header height is **dynamic** —
measured from the header's own content, no `height` prop. Each governed page reserves a
**tight** band equal to its active header's measured height.

Why this is achievable cheaply: page N's band depends only on which header is active
_entering_ page N, which depends only on anchors that landed on pages < N — all already
committed by the time iText lays out page N. So it resolves in **one forward layout
pass** via the custom `DocumentRenderer` per-page hook. No fixed point, no second header
pass.

### Confirmed decisions (all locked with the user)

1. **Scope**: new follow-up change from `main`; shipped branch untouched.
2. **Height**: dynamic — measured from header content; the `height` prop/inspector field
   is removed. Tight per-page band.
3. **Model**: marker/running-header — header drawn in the top band; anchor selects which.
4. **Anchor in loop/datatable**: allowed silently, first-class (one promotion per
   rendered occurrence). No warning, no block.
5. **Switch timing**: a switch takes effect **on the page after** the anchor's landing
   page ("the running header is the section you were in _entering_ the page"). Anchors
   that appear before any content (declared at document top) are active from page 1.
   This is the standard book/LaTeX convention and is what makes the single pass possible.
6. **Passes**: the header feature adds **zero** extra passes (single forward sweep). The
   only second pass that can occur is the _pre-existing, unrelated_ `sys.pages.total`
   two-pass, which the header mechanism free-rides on.

## Switch rule (deterministic, pure — the unit-testable core)

`computeActiveHeaderEnteringPage(N, landings, leadingHeaderId): String?`

- `landings` = ordered list of `(headerNodeId, flowOrdinal, landingPage)` for every
  rendered `pageheader` occurrence (markers fire in flow order during the single sweep;
  loops/datatables produce one entry per iteration; a false conditional produces none).
- A header anchor that lands on page `p` becomes **eligible** starting page `p+1`.
- Maintain a FIFO of eligible-but-not-yet-promoted headers (flow order) and `active`.
  For each page `q` ascending: promote (dequeue) **one** eligible header if any →
  `active`. `H(q) = active`.
- `leadingHeaderId` = the last `pageheader` that appears in flow order **before any
  content-bearing node** (a pure static pre-walk of the document; conditional/loop-
  wrapped leading nodes are treated conservatively as _not_ leading). If present it is
  `active` from page 1.
- An anchor on the final page (no following page) is never shown — nothing to head.

Both the band-reservation hook and the END_PAGE painter call this same function for page
N, so the reserved band always equals the painted header.

Worked example — markers land on pages `H_intro@2, H_terms@4, H_appx@4, H_final@7`, no
leading header, 7-page doc:

| Page | Eligible (landed ≤ p−1) | Promoted | H(p)           |
| ---- | ----------------------- | -------- | -------------- |
| 1    | —                       | —        | none (no band) |
| 2    | —                       | —        | none           |
| 3    | H_intro                 | H_intro  | H_intro        |
| 4    | —                       | —        | H_intro        |
| 5    | H_terms, H_appx         | H_terms  | H_terms        |
| 6    | (H_appx)                | H_appx   | H_appx         |
| 7    | —                       | —        | H_appx         |

(Two anchors collide on page 4 → both still shown, cascaded one per page from page 5 —
preserves the "push the second to the next page" intent, single-pass.)

Reductions: a single `pageheader` declared at document top ⇒ `leadingHeaderId` ⇒ header
on every page = today's single-header behavior (regression-tested).

## Architecture (single pass)

### Step 0 — measure header heights (cheap, NOT a pass)

For each `pageheader` node: build its renderer subtree (existing registry path) and
`layout()` it in isolation against `pageContentWidth × unbounded` (width = pageSize −
static L/R margins, already computed today). Read `getOccupiedArea().getBBox().height`
and add the `marginTop` cascade (`effectivePageMarginPt(node,"marginTop")`, reused
as-is). Result: `Map<headerNodeId, bandHeightPt>`, computed once before layout. N tiny
element layouts — not a document pass. (`parseNodeHeight` is no longer used for headers.)

### Step 1 — anchor marker (records landing pages live)

`PageHeaderNodeRenderer.render` currently returns `emptyList()` (verified). Change it to
return one zero-footprint marker at the anchor's flow position (the registry flat-maps a
child's render output into parent flow, so it lands exactly where the node sits — works
inside containers/loops/conditionals/stencils transparently).

- **New `AnchorMarkerElement.kt`**: `AnchorMarkerDiv` (`Div`, height 0,
  no margin/padding/border, `keepTogether(true)`, no children) + `AnchorMarkerRenderer
extends DivRenderer`. `layout()`: `super.layout`, read occupied page number, record
  `(headerNodeId, flowOrdinal, page)` into the sink, then return a zero-height occupied
  area (zero layout perturbation). `draw()` = no-op.
- Boundary attribution: a zero-height box at an exhausted area resolves to the _next_
  page (the page where following content begins). Deterministic; documented; stable
  because there is only ONE layout (no cross-pass comparison).
- **New `AnchorLandingSink.kt`**: per-render mutable holder; `record(...)`,
  `landingsUpToPage(p)`, `nextOrdinal()`. Populated incrementally by the forward sweep.

### Step 2 — per-page tight band (custom DocumentRenderer)

`iText`'s `Document.setMargins` is document-scoped (`DirectPdfRenderer.kt:364`); the
shipped branch fakes page 1 with a spacer Div. Replace that entirely.

- **New `BandedDocumentRenderer.kt`**: `class BandedDocumentRenderer(document) :
DocumentRenderer(document)`. Override the per-page area hook (`updateCurrentArea`):
  for the page `N` whose area is being created, all content on pages < N is already
  laid out, so `sink.landingsUpToPage(N-1)` is complete. Compute
  `computeActiveHeaderEnteringPage(N, …)`; band = `heights[activeId]` (Step 0 map) or
  the plain top margin if none. `super.updateCurrentArea(...)`, then shrink the area's
  bbox from the top by that band; preserve bottom (footer) + L/R exactly. Installed via
  `iTextDocument.setRenderer(...)` in `performRenderWithContext`.
- Page 1 uses `leadingHeaderId` (static, known before any layout) — no circularity.
- **Delete** `HeaderBands`, `firstPageSpacer`, `computeHeaderBands`, and the spacer-Div
  block (`performRenderWithContext` ~398-416). Footer/`PageFooterEventHandler`
  untouched ⇒ no footer regression.

### Step 3 — paint (END_PAGE, same single pass)

- `RenderContext.kt`: add `anchorLandingSink: AnchorLandingSink?`,
  `headerHeights: Map<String,Float>?`, `leadingHeaderId: String?` + `withAnchorSink`,
  `withHeaderHeights`, mirroring the existing `withTotalPages`/`withPageParams`.
- `PageHeaderEventHandler.kt`: drop the `headerNodeIds` ctor param and the
  `init { require(size<=2) }`. On `END_PAGE` for page N: `val id =
computeActiveHeaderEnteringPage(N, sink.landingsUpToPage(N-1), leadingHeaderId)
?: return`; look up `document.nodes[id]`; existing rectangle/Canvas band-drawing math
  unchanged (it already sizes from the node). Reserved band (Step 2) == painted band by
  construction (same function, same height map).
- `DirectPdfRenderer`: replace `pageHeaderNodesInDocumentOrder` with
  `pageHeaderAnchorsInFlowOrder` (collect every `pageheader` anywhere; **no
  cardinality/root checks, no exceptions**) — used only for the zero-anchor fast path
  (no anchors ⇒ none of the new machinery engages ⇒ identical to today; no perf
  regression) and the static `leadingHeaderId` pre-walk.

### Pass count

- No `pageheader`: unchanged from today.
- With `pageheader`, no `sys.pages.total`: **1 pass**.
- With `pageheader` + `sys.pages.total`: **2 passes** (the pre-existing total-pages
  two-pass; the header mechanism adds nothing — it just runs inside whichever pass(es)
  already happen).

## Layers to change (single source of truth going forward)

| Layer             | File(s)                                                                                                                                                                                                                                                            | Change                                                                                                                                                                                                                                                                |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Editor registry   | `modules/editor/src/main/typescript/engine/registry.ts`                                                                                                                                                                                                            | Remove `pageheader` from `isAnchoredPageBlock` (footer stays); add `isFlowAnchor(t)=>t===PAGE_HEADER_TYPE`; delete `maxInstancesPerDocument:2`; **remove the `height` inspector field** (dynamic now); rewrite/add `examples` to a mid-document running-header switch |
| Editor commands   | `modules/editor/src/main/typescript/engine/commands.ts`                                                                                                                                                                                                            | Delete the `PAGE_HEADER_TYPE` branches in `applyInsertNode`/`applyMoveNode` and the header rule in `validateRootContentBoundaries`. Footer rules unchanged                                                                                                            |
| Editor DnD        | `modules/editor/src/main/typescript/dnd/drop-logic.ts`, `EpistolaPalette.ts`, `EpistolaEditor.ts`                                                                                                                                                                  | Header droppable anywhere a normal block is (footer still root-restricted via narrowed `isAnchoredPageBlock`); remove header-zone insert-index special cases                                                                                                          |
| Backend validator | `modules/epistola-core/.../validation/PageHeaderCardinalityValidator.kt` + `UpdateDraft.kt`                                                                                                                                                                        | **Delete** the validator + its `UpdateDraftHandler` injection/call (nothing left to enforce: unbounded, anywhere, loops allowed). Remove/rewrite `PageHeaderCardinalityValidatorTest.kt`                                                                              |
| Renderer          | `modules/generation/.../DirectPdfRenderer.kt`, `PageHeaderEventHandler.kt`, `PageHeaderNodeRenderer.kt`, `RenderContext.kt` + 3 new files                                                                                                                          | Per Architecture Steps 0–3                                                                                                                                                                                                                                            |
| MCP               | `modules/epistola-mcp/.../dto/ComponentTypeInfo.kt` (+ tests)                                                                                                                                                                                                      | `pageheader` `maxInstancesPerDocument` → `null`; update `list_component_types` prose/assertions (no "max 1/2", no `height`)                                                                                                                                           |
| Demo catalog      | `modules/epistola-core/src/main/resources/epistola/catalogs/demo/...`                                                                                                                                                                                              | Remove `height` from demo headers (ignored if left); add a demo template exercising a mid-flow running-header switch; bump `catalog.json` `release.version` + `updatedAt` (CLAUDE.md rule #9)                                                                         |
| Docs              | `docs/generation.md` (rewrite header section: single-pass incremental algorithm, the switch rule + worked table, dynamic measured height, the static page-1 rule), `docs/editor-features.md`, `CHANGELOG.md`, new `docs/adr/0003-flow-anchored-running-headers.md` |

## Editor UX (minimal)

A `pageheader` is now an inline flow anchor that also governs the top band from its
position onward, with **content-driven height** (no height control). v1: render it at its
real flow position in tree & canvas with distinct "anchor" chrome + label ("Page header
— active from the next page"); do not float it to the page top in the editor; no height
resize handle. Drive affordances off `isFlowAnchor`. No header-range picker / header-only
reorder UI (future). Files: `registry.ts`, `EpistolaEditor.ts`, `EpistolaPalette.ts`, the
canvas block component, `drop-logic.ts`.

## Testing strategy

- **Unit**: `PageHeaderScheduleRuleTest` — reproduce the worked table exactly;
  leading-header ⇒ all pages; single anchor at top == today; same-page collision ⇒
  cascade one-per-page from next page; anchor on last page not shown; zero occurrences;
  loop produces N ordered entries. Editor command/DnD specs: header insert/move
  anywhere succeeds, >2 allowed, footer still fixed.
- **Generation integration** (extend `PageHeaderFooterTest`; reuse
  `extractFirstBaselineYOnPage`): switch lands one page after the anchor; per-page tight
  band — body baseline on each page sits under _that_ page's measured header, not a max;
  dynamic height — change header content, band auto-resizes, no overlap/clipping;
  header-in-loop (per-iteration switches, deterministic); no-header pages before first
  eligible header (full-height body); leading-header == today's single header (golden);
  footer non-regression (existing footer tests pass unchanged); marker zero-perturbation
  (header-less doc body-baseline delta < 0.5pt with vs without the marker path);
  determinism (same input ⇒ byte-identical output, single pass so trivially stable);
  `DocumentRenderer.updateCurrentArea` per-page-hook probe test.
- **MCP**: `pageheader` `maxInstancesPerDocument == null`; description reflects flow
  anchor + dynamic height.
- **Golden/visual**: regenerate pageheader-template goldens; add a mid-flow running-
  header golden.
- Gates: `./gradlew unitTest integrationTest`, generation suite, editor `pnpm test`,
  `./gradlew test --tests UiRestApiSeparationTest`, `ktlintCheck`, `pnpm format:check`.

## Verification (end-to-end)

1. `pnpm install && pnpm build` + `./gradlew build`.
2. Editor: drop a `pageheader` mid-document and inside a container; confirm >2 allowed,
   no height field, footer still pinned, anchor labelled.
3. Generate via UI preview + REST + MCP `preview_document`: per-page header switches one
   page after each anchor; each governed page's body sits tight under its measured
   header; header content change auto-resizes the band.
4. Header inside a loop: header switches per iteration as expected.
5. Catalog import/export round-trip of the new demo template stays consistent
   (`CatalogExportImportTest`).

## Risks & open items

- **R1**: overriding `DocumentRenderer.updateCurrentArea` is internal iText API,
  version-sensitive. Mitigation: isolate all iText-internal coupling in
  `BandedDocumentRenderer`; dedicated per-page-hook probe test. (No convergence/
  oscillation risk — single pass.)
- **R2**: marker leading perturbation in exotic layout contexts (columns/flex).
  Mitigation: zero height/leading, `keepTogether`; baseline-delta probe test.
- **R3**: in-isolation header measurement must use the true content width and the same
  style/theme context as the band paint, or reserved ≠ painted. Mitigation: both use the
  shared height map + identical context; assert equality in tests.
- **Open (assumed; correct at review if wrong)**: switching is page-granular (never
  mid-page) — yes, per decisions; footers stay non-anchored (out of scope) — confirmed;
  `isAnchoredPageBlock` narrowed to footer rather than renamed (keeps call sites stable)
  — implementer's choice.

## Critical files

- `modules/generation/src/main/kotlin/app/epistola/generation/pdf/DirectPdfRenderer.kt`
- `modules/generation/src/main/kotlin/app/epistola/generation/pdf/PageHeaderEventHandler.kt`
- `modules/generation/src/main/kotlin/app/epistola/generation/pdf/PageHeaderNodeRenderer.kt`
- `modules/generation/src/main/kotlin/app/epistola/generation/pdf/RenderContext.kt`
- `modules/editor/src/main/typescript/engine/commands.ts`
- `modules/editor/src/main/typescript/engine/registry.ts`
- `modules/editor/src/main/typescript/dnd/drop-logic.ts`
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/templates/validation/PageHeaderCardinalityValidator.kt` (delete) + `.../commands/versions/UpdateDraft.kt`
- New: `AnchorMarkerElement.kt`, `AnchorLandingSink.kt`, `BandedDocumentRenderer.kt`, `PageHeaderScheduleRule` (pure fn), `docs/adr/0003-flow-anchored-running-headers.md`
