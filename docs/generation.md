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

| Format | Renderer | Use Case |
|--------|----------|----------|
| **HTML** | HtmlRenderer | Web display, email, preview |
| **PDF (direct)** | DirectPdfRenderer + iText Core | Fast PDF, simple layouts |
| **PDF (via HTML)** | HtmlRenderer + iText pdfHTML | Good CSS support, pure JVM |
| **PDF (via HTML)** | HtmlRenderer + Playwright | Best CSS fidelity, needs Chromium |

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Rendering location | Server-side (Kotlin) | Single source of truth, security, control |
| Primary PDF engine | iText Core (direct) | Fast, pure JVM, no external dependencies |
| Fallback PDF engine | Playwright | Complex CSS layouts when needed |
| Editor preview | WebSocket to server | Real-time preview with server rendering |
| MVP approach | Synchronous | Simple first, async later |

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

| Block Type | iText Element |
|------------|---------------|
| `text` | `Paragraph` with styled `Text` elements |
| `container` | `Div` |
| `columns` | `Table` with column layout |
| `table` | `Table` with cells |
| `conditional` | Render matching branch or nothing |
| `loop` | Repeat child elements for each item |

### Expression Evaluation

Expressions like `{{customer.name}}` are evaluated server-side using a simple path resolver:

```kotlin
class ExpressionEvaluator {
    fun evaluate(expression: String, data: Map<String, Any?>): Any? {
        // Parse "customer.name" -> ["customer", "name"]
        // Navigate through data map
        // Return resolved value or null
    }
}
```

No JavaScript execution needed - just object path traversal.

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

| Backend | When to use |
|---------|-------------|
| iText pdfHTML | Good CSS support, pure JVM |
| Playwright | Complex CSS (flexbox, grid), needs Chromium |

### Async Job Queue

For high-volume or batch rendering:
- Submit job, get UUID
- Poll for status
- Download when complete

## Performance Expectations

| Renderer | Simple Doc | Complex Doc |
|----------|------------|-------------|
| iText Core (direct) | 10-50ms | 50-200ms |
| iText pdfHTML | 50-150ms | 150-400ms |
| Playwright | 200-400ms | 400-800ms |

## Related Documentation

- [Roadmap](./roadmap.md) - Overall project phases
- [API Specification](../modules/api-spec/) - OpenAPI definitions