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

### Page Headers (first-page variant)

A template may declare up to two `pageheader` nodes as direct children of the
root slot. Mapping to physical pages is **positional** — the order of header
nodes in the root slot's `children` array selects which header applies where:

| Header count | Page 1                 | Page 2 and onward       |
| ------------ | ---------------------- | ----------------------- |
| 0            | (no header)            | (no header)             |
| 1            | the sole `pageheader`  | the sole `pageheader`   |
| 2            | the first `pageheader` | the second `pageheader` |

No second pass over the document is required to make this decision: the
`PageHeaderEventHandler` runs at iText's END_PAGE event and selects the right
header node from the current page number. Each page's body sits below its own
header band: iText's document margin is set to the running header band, and a
zero-opacity spacer Div is prepended to the body flow sized to the extra height
that the first-page header needs. The spacer is consumed on page 1, so pages 2+
start cleanly at the running band — a tall cover header doesn't leak whitespace
onto running pages.

Cardinality (max 2) and root-level placement are enforced server-side by
`PageHeaderCardinalityValidator` before any draft update reaches the renderer.

Out of scope for this iteration (filed as follow-ups): last-page header,
per-section / page-range headers, odd/even alternating headers, and the same
variant model for footers.

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
- [API Specification](../modules/api-spec/) - OpenAPI definitions
