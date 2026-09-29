# Document Generation Architecture

This document describes the architecture for document generation (PDF/HTML) in Epistola Suite.

## Overview

Epistola Suite uses **server-side rendering in Kotlin** for all document generation. All rendering happens on the backend with no client-side JavaScript required for output.

## Rendering Paths

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Rendering Architecture                                │
│                                                                              │
│  ┌──────────────────┐                                                        │
│  │ Template Model   │                                                        │
│  │ + Input Data     │                                                        │
│  └────────┬─────────┘                                                        │
│           │                                                                  │
│           ├──────────────────────────────────────────┐                       │
│           │                                          │                       │
│           ▼                                          ▼                       │
│  ┌─────────────────────┐                   ┌─────────────────────┐           │
│  │ HtmlRenderer        │                   │ DirectPdfRenderer   │           │
│  │ (Kotlin)            │                   │ (iText Core)        │           │
│  └──────────┬──────────┘                   └──────────┬──────────┘           │
│             │                                         │                      │
│             ▼                                         │                      │
│  ┌─────────────────────┐                              │                      │
│  │ HTML Output         │                              │                      │
│  └──────────┬──────────┘                              │                      │
│             │                                         │                      │
│       ┌─────┴─────┐                                   │                      │
│       │           │                                   │                      │
│       ▼           ▼                                   ▼                      │
│  ┌─────────┐ ┌──────────────┐              ┌─────────────────────┐           │
│  │ iText   │ │ Playwright   │              │ PDF Output          │           │
│  │ pdfHTML │ │ (Chromium)   │              │ (direct, fastest)   │           │
│  └────┬────┘ └──────┬───────┘              └─────────────────────┘           │
│       │             │                                                        │
│       ▼             ▼                                                        │
│  ┌─────────────────────┐                                                     │
│  │ PDF Output          │                                                     │
│  │ (from HTML)         │                                                     │
│  └─────────────────────┘                                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Output Formats

| Format             | Renderer                       | Use Case                          |
| ------------------ | ------------------------------ | --------------------------------- |
| **HTML**           | HtmlRenderer                   | Web display, email, preview       |
| **PDF (direct)**   | DirectPdfRenderer + iText Core | Fast PDF, simple layouts          |
| **PDF (via HTML)** | HtmlRenderer + iText pdfHTML   | Good CSS support, pure JVM        |
| **PDF (via HTML)** | HtmlRenderer + Playwright      | Best CSS fidelity, needs Chromium |

## Key Decisions

| Decision            | Choice               | Rationale                                 |
| ------------------- | -------------------- | ----------------------------------------- |
| Rendering location  | Server-side (Kotlin) | Single source of truth, security, control |
| Primary PDF engine  | iText Core (direct)  | Fast, pure JVM, no external dependencies  |
| Fallback PDF engine | Playwright           | Complex CSS layouts when needed           |
| Editor preview      | WebSocket to server  | Real-time preview with server rendering   |
| MVP approach        | Synchronous          | Simple first, async later                 |

## MVP: Synchronous PDF Preview

For MVP, we start with a simple synchronous endpoint using iText Core for direct PDF generation.

### API Endpoint

```http
POST /api/v1/tenants/{tenantId}/templates/{templateId}/variants/{variantId}/preview
Content-Type: application/json

{
  "customer": { "name": "John Doe" },
  "items": [
    { "description": "Widget", "price": 10.00 }
  ]
}

Response 200:
Content-Type: application/pdf
Content-Disposition: inline; filename="preview.pdf"
[PDF bytes]
```

### Components

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Spring Boot                                  │
│                                                                      │
│  ┌────────────────────┐                                              │
│  │ PreviewController  │                                              │
│  │ POST .../preview   │                                              │
│  └─────────┬──────────┘                                              │
│            │                                                         │
│            ▼                                                         │
│  ┌────────────────────┐    ┌─────────────────────────────────────┐  │
│  │ TemplateService    │───►│ Load TemplateVersion + TemplateModel│  │
│  └─────────┬──────────┘    └─────────────────────────────────────┘  │
│            │                                                         │
│            ▼                                                         │
│  ┌────────────────────┐    ┌─────────────────────────────────────┐  │
│  │ ExpressionEvaluator│───►│ Evaluate {{expressions}} in blocks  │  │
│  └─────────┬──────────┘    └─────────────────────────────────────┘  │
│            │                                                         │
│            ▼                                                         │
│  ┌────────────────────┐    ┌─────────────────────────────────────┐  │
│  │ DirectPdfRenderer  │───►│ Convert blocks to iText elements    │  │
│  │ (iText Core)       │    │ - TextBlock → Paragraph             │  │
│  └─────────┬──────────┘    │ - TableBlock → Table                │  │
│            │               │ - ColumnsBlock → MultiColumn         │  │
│            ▼               │ - etc.                               │  │
│  ┌────────────────────┐    └─────────────────────────────────────┘  │
│  │ PDF bytes          │                                              │
│  └────────────────────┘                                              │
└─────────────────────────────────────────────────────────────────────┘
```

### Block Rendering (iText)

| Block Type    | iText Element                           |
| ------------- | --------------------------------------- |
| `text`        | `Paragraph` with styled `Text` elements |
| `container`   | `Div`                                   |
| `columns`     | `Table` with column layout              |
| `table`       | `Table` with cells                      |
| `conditional` | Render matching branch or nothing       |
| `loop`        | Repeat child elements for each item     |

### Expression Evaluation

Epistola supports two expression languages, with the language stored in the `Expression` model:

```kotlin
enum class ExpressionLanguage {
    Jsonata,    // Default, more ergonomic for designers
    JavaScript, // Full JS power for advanced users
}

data class Expression(
    val raw: String,
    val language: ExpressionLanguage = ExpressionLanguage.Jsonata,
)
```

#### JSONata (Recommended)

Concise syntax purpose-built for JSON data transformation:

```jsonata
customer.name                           // Property access
items[active]                           // Filter: items where active is true
items.price                             // Map: extract price from each item
$sum(items.price)                       // Aggregation
first & " " & last                      // String concatenation
$formatNumber(price, "#,##0.00")        // Number formatting
active ? "Yes" : "No"                   // Conditional
```

Implementation: [Dashjoin JSONata](https://github.com/dashjoin/jsonata-java) (Java)

#### JavaScript

Full JavaScript for power users:

```javascript
customer.name; // Property access
items.filter((x) => x.active); // Filter
items.map((x) => x.price); // Map
items.reduce((sum, x) => sum + x.price, 0); // Aggregation
first + " " + last; // String concatenation
price.toFixed(2); // Number formatting
```

Implementation: GraalJS with sandbox (no file/network access, execution limits)

#### System Parameters

System parameters are runtime values provided by the rendering engine:

| Parameter           | Type                  | Description                                                          |
| ------------------- | --------------------- | -------------------------------------------------------------------- |
| `sys.render.time`   | string (ISO-8601 UTC) | Render timestamp. Use `$formatDate()` for locale-specific formatting |
| `sys.pages.current` | integer               | Current page number (available in headers/footers only)              |
| `sys.pages.total`   | integer               | Total number of pages in the document                                |

Example:

```jsonata
$formatDate(sys.render.time, "dd-MM-yyyy")       // "03-04-2026"
$formatDate(sys.render.time, "dd-MM-yyyy HH:mm") // "03-04-2026 10:30"
```

#### Evaluator Architecture

```
┌─────────────────────────────────────────────────────────┐
│ CompositeExpressionEvaluator                            │
│                                                         │
│   expression.language == Jsonata?                       │
│           │                                             │
│     ┌─────┴─────┐                                       │
│     ▼           ▼                                       │
│ ┌─────────┐ ┌──────────────┐                            │
│ │ Jsonata │ │ GraalJS      │                            │
│ │Evaluator│ │ Evaluator    │                            │
│ │         │ │ (sandboxed)  │                            │
│ └─────────┘ └──────────────┘                            │
└─────────────────────────────────────────────────────────┘
```

### Page headers and footers

A template may contain any number of `pageheader` and `pagefooter` nodes, anywhere a block can go:
inside stencils, conditionals, loops and containers, but never inside another header or footer.
Where one sits decides which pages it applies to. The design and the alternatives weighed are in
[ADR 0027](adr/0027-page-headers-and-footers-by-section.md).

**Page breaks divide the rendered flow into sections.** A page break inside a false conditional
does not divide it, and one inside a loop divides it once per iteration. A block is _at the start
of its section_ when nothing that draws content comes before it in the section; containers,
stencils, conditionals, loops and other headers or footers do not count as content.

Headers apply to what comes after them:

- At the start of a section (the document start, or right after a page break) a header applies
  from that page. Several there in a row form a first-page variant: the first applies to the
  section's first page, the next to its second page, and the last continues from there.
- After content, a header takes over from the page after the one it lands on. Several landing on
  one page take over one page at a time, in flow order.
- A header lasts until the next one takes over. Pages before the first header have none.

Footers apply to what comes before them:

- A footer covers the pages of its own section, wherever in the section it sits, so the natural
  place is at the bottom.
- It also covers the sections above it that have no footer of their own, with its running (last)
  footer. Pages after the last footer have none.
- Several footers in one section form a first-page variant, in flow order. The portable validator
  warns (`PAGEFOOTER_NOT_ADJACENT`) when they are not adjacent children of one slot.

Footers cannot switch in the middle of a section: a page's bottom band is reserved before the page
is laid out, and the footer covering it is the next one _below_ its content, not yet laid out.
Headers can, because the header that takes over on page N landed on an earlier page.

`hideOnFirstPage` keeps its meaning: page 1 of the document. An empty header or footer still
reserves its `height`; set `height` to `0` to switch a band off.

Every document the positional model accepted keeps its layout: two headers at the top are a
first-page variant with the second running to the end, and one footer at the end covers every
section above it. `PageBandParityTest` proves this against the positional renderer.

**How it renders** (single pass; `PageBands.kt`, `PageBandElements.kt`):

1. **Collect.** The body render carries a `PageBandCollector` in `RenderContext.pageBands`. A page
   break emits a `SectionPageBreak` and starts a section; a header or footer emits a zero-height
   `PageBandAnchor` and registers a `PageBandOccurrence` with its section, whether it is at the
   section start, and its data scope (a loop gives one occurrence per iteration). Band content and
   band measurement render without the collector.
2. **Measure.** Each occurrence is measured with its own data scope (see auto-grow below).
3. **Schedule.** `PageBandSchedule` is a pure function of the occurrences. The margins function
   installed with `Document.setPageMargins { page -> … }` asks it for each page as iText creates
   the page: the pending `SectionPageBreak` says which section starts there, and every header
   anchor drawn on an earlier page has recorded its landing page in `draw()` by then.
4. **Paint.** `PageBandEventHandler` paints the header and footer the schedule chose for the page,
   so the painted band is always the one whose height the page reserved.

The iText behaviour this relies on is pinned by `PageBandItextProbeTest`.

**Versions published before this model** (`RenderingDefaults` V1–V3) keep the positional model: at
most two root-level headers (first for page 1, second for pages 2–N) and one footer on every page,
with the renderer rejecting anything else. `RenderingDefaults.sectionPageBands` selects the model;
V4 turns it on.

### Header & footer band height (auto-grow)

A `pageheader` / `pagefooter` carries an optional `height` prop (e.g. `"60pt"`).
That height is a **minimum**, not a fixed clip: the band reserves
`max(configured height, measured content height)`. So increasing `height` adds
whitespace, content shorter than it is unaffected, and content **taller** than it
grows the band instead of being dropped. The decision and the options weighed
(clip, warn, reject, auto-grow) are recorded in
[ADR 0008](adr/0008-header-footer-height-minimum.md).

Why this matters: a header/footer is drawn as an iText overlay into a fixed
`Canvas` rectangle. If content overflows that rectangle, iText discards the
overflow — so a header sized smaller than its content (a letterhead with a logo,
an address block, several lines) would silently render blank. Auto-grow removes
that failure mode.

How it works — a small pre-pass before the real render:

1. **Measure** (`DirectPdfRenderer.measureBandHeights`). For each header
   and footer (each occurrence, with its own data scope, in the section model), build the exact content wrapper the event handler will draw
   (`buildBandWrapper`, shared with the handlers so measured == rendered) and lay
   it out via an iText **dry layout** (`measureBandContentHeight`:
   `renderer.layout(...)` into a tall area, read `occupiedArea.bBox.height` — draws
   nothing). The pass runs in a throwaway `PdfDocument` with its **own**
   `FontCache`, because `PdfFont`s are bound to a single document and must not leak
   into the real render. It runs only when the document has a header or footer, and
   any measurement error falls back to the configured/default height, so it can
   never make a previously-working render fail.
2. **Resolve.** The effective heights drive both the page's top/bottom band and the
   rectangle the band is painted into, so the reserved space and the drawn band always
   agree. In the section model the margins function reserves each page's band from the
   height of the occurrence the schedule chose; in the positional model
   (`resolveBandLayout` → `computeHeaderBands`) the running header sets the document
   margin and a page-1 spacer covers a taller first-page header.
3. **Render.** `paintHeaderBand` / `paintFooterBand` (shared by both models) set
   `OVERFLOW_Y/X = VISIBLE` on the band canvas as a safety net so content is never
   silently dropped even if a measurement edge case under-sizes the band.

### Address blocks vs. header/footer bands

An `addressblock` is a **page-absolute** element: its window is drawn at a fixed
page position by `AddressBlockEventHandler`, and an in-flow spacer reserves the
window's height in the **body** so body text clears it. `hoistAddressBlock` moves
the address block to the document root so the body owns that reservation.

Two consequences for bands, both handled so an address block authored _inside_ a
header/footer doesn't break the layout:

- **The graph is hoisted before the bands are measured and rendered.** Each render
  path hoists once (`renderDocument`) and passes that graph to band measurement,
  the band schedule, the event handlers and the body alike. So an address block
  nested in a header/footer is moved to the body and never renders inside the band
  — otherwise its ~window-height spacer would inflate the band with empty space.
- **The body reservation respects the real header height.** The address block
  reserves down to its window bottom **relative to** the page-1 body-content top:
  page margin + the effective band of the header that applies on page 1. The
  positional model passes it in `RenderContext.bodyContentTopPt`; the section model
  knows it only once every header is measured, so the address block registers a
  `PageBandCollector.onPageOneBodyTop` callback that sets its reservation then. The
  address block's aside renders without the collector, so it never ends the start of
  the first section. Under a header tall
  enough to already cover the window, the reservation shrinks to zero instead of
  always reserving the full window height from the raw `height` prop.

Note that an address block's fixed window position (`top`/`height`, in mm from the
page top) can still geometrically overlap a tall header — address blocks are body
elements and aren't _meant_ to live inside a header; the renderer just no longer
blows the layout apart when one does.

Code map: `HeaderFooterBand.kt` (`buildBandWrapper`, `measureBandContentHeight`,
`paintHeaderBand`, `paintFooterBand`), `PageBands.kt` (`PageBandCollector`,
`PageBandSchedule`), `PageBandElements.kt`, `PageBandEventHandler`,
`DirectPdfRenderer.kt` (`bandPlan`, `renderWithSectionBands`, `measureBandHeights`,
`resolveBandLayout`, `computeHeaderBands`, `hoistAddressBlock`,
`performRenderWithContext`), `PageHeaderEventHandler` / `PageFooterEventHandler`
(positional model), `AddressBlockNodeRenderer`, and `RenderContext.bodyContentTopPt`.

### Page Settings

```kotlin
data class PageSettings(
    val format: PageFormat = PageFormat.A4,
    val orientation: Orientation = Orientation.PORTRAIT,
    val margins: Margins = Margins()
)

enum class PageFormat(val width: Float, val height: Float) {
    A4(595f, 842f),      // points (72 per inch)
    LETTER(612f, 792f)
}
```

## Future: Additional Renderers

### HtmlRenderer (Kotlin)

Server-side HTML generation for:

- Web preview (via WebSocket)
- Email output
- HTML-to-PDF conversion

### HTML-to-PDF Backends

| Backend       | When to use                                 |
| ------------- | ------------------------------------------- |
| iText pdfHTML | Good CSS support, pure JVM                  |
| Playwright    | Complex CSS (flexbox, grid), needs Chromium |

### Async Job Queue

For high-volume or batch rendering:

- Submit job, get UUID
- Poll for status
- Download when complete

## Performance Expectations

| Renderer            | Simple Doc | Complex Doc |
| ------------------- | ---------- | ----------- |
| iText Core (direct) | 10-50ms    | 50-200ms    |
| iText pdfHTML       | 50-150ms   | 150-400ms   |
| Playwright          | 200-400ms  | 400-800ms   |

## Related Documentation

- [Roadmap](./roadmap.md) - Overall project phases
- [REST API](../modules/rest-api/) - Controllers implementing the `epistola-contract` OpenAPI surface
