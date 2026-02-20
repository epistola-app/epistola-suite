# PDF/A-2b Compliance

Epistola Suite supports generating PDF/A-2b (ISO 19005-2, Level B) compliant documents for long-term archival. This is an opt-in setting per template, disabled by default.

## What is PDF/A?

PDF/A is an ISO-standardized subset of PDF designed for long-term digital preservation. PDF/A-2b (Level B) guarantees:

- **Self-contained documents** — all fonts are embedded, no external dependencies
- **Color consistency** — an ICC color profile is included (sRGB IEC61966-2.1)
- **Metadata** — XMP metadata is embedded for discoverability
- **Reproducible rendering** — the document looks the same on any viewer, indefinitely

This matters for regulated industries (finance, healthcare, government) and any scenario where documents must remain readable for years or decades.

## Standard vs PDF/A output

| Aspect | Standard PDF (default) | PDF/A-2b |
|--------|----------------------|----------|
| Fonts | Helvetica (non-embedded, standard 14) | Liberation Sans (embedded TTF) |
| Color profile | None | sRGB ICC profile embedded |
| XMP metadata | No | Yes (pdfaid:part, pdfaid:conformance) |
| File size | Smaller (~5-15 KB for simple docs) | Larger (~400+ KB due to embedded fonts) |
| Render speed | Faster | Slower (font embedding + ICC) |
| Archival safe | No | Yes (ISO 19005-2 Level B) |

## Enabling PDF/A

### Per template (UI)

1. Open a template's detail page
2. Go to the **Settings** tab
3. Under **Output Settings**, check **PDF/A-2b compliance**

The setting takes effect immediately for new batch/API generations. Preview rendering in the editor always uses standard PDF for speed.

### Per template (API)

```http
PATCH /tenants/{tenantId}/templates/{templateId}
Content-Type: application/json

{ "pdfaEnabled": true }
```

## How it works

### Render pipeline

When a document is generated, the `pdfaCompliant` flag flows through the render chain:

```
DocumentGenerationExecutor
  └─ reads template.pdfaEnabled
  └─ GenerationService.renderPdf(pdfaCompliant = template.pdfaEnabled)
       └─ DirectPdfRenderer.render(pdfaCompliant = ...)
            ├─ pdfaCompliant=true  → PdfADocument + embedded fonts + ICC profile
            └─ pdfaCompliant=false → PdfDocument + standard Helvetica
```

Preview rendering (`TemplatePreviewHandler`) always uses the default `pdfaCompliant = false`, so previews are fast regardless of the template setting.

### Font strategy

`FontCache` manages font creation based on the compliance mode:

| Mode | Font family | Strategy |
|------|-------------|----------|
| Standard | Helvetica (built-in) | Non-embedded, references PDF standard 14 fonts |
| PDF/A | Liberation Sans (TTF) | Force-embedded via `PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED` |

Liberation Sans is metrically compatible with Helvetica — documents render identically in both modes, but PDF/A embeds the actual font data (~400 KB for all 4 variants).

Font variants available: regular, bold, italic, bold-italic.

### Document metadata

Both modes set document metadata (title, author, subject, creator) via the PDF info dictionary. In PDF/A mode, this metadata is additionally written as XMP (required by the standard).

Metadata is populated from:
- **title** — template name
- **author** — tenant name
- **creator** — "Epistola Suite" (default)

### ICC color profile

PDF/A requires an output intent declaring the color space. Epistola embeds the sRGB IEC61966-2.1 ICC profile (bundled at `generation/src/main/resources/color/sRGB.icc`).

## Database

The setting is stored as a boolean column on the `document_templates` table:

```sql
-- V10__template_pdfa_setting.sql
ALTER TABLE document_templates ADD COLUMN pdfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
```

## Key files

| File | Purpose |
|------|---------|
| `modules/generation/.../pdf/DirectPdfRenderer.kt` | Branches between `PdfDocument` and `PdfADocument` |
| `modules/generation/.../pdf/FontCache.kt` | Dual font strategy (embedded vs standard) |
| `modules/generation/.../pdf/PdfMetadata.kt` | Metadata model |
| `modules/generation/src/main/resources/fonts/` | Liberation Sans TTF fonts (SIL OFL license) |
| `modules/generation/src/main/resources/color/sRGB.icc` | ICC color profile |
| `modules/epistola-core/.../templates/DocumentTemplate.kt` | `pdfaEnabled` field |
| `modules/epistola-core/.../documents/batch/DocumentGenerationExecutor.kt` | Reads `template.pdfaEnabled` for batch jobs |

## Limitations

- **Preview is always standard PDF** — PDF/A is only applied during batch/API generation, not in the editor preview. This is intentional for performance.
- **Font family is fixed** — Liberation Sans / Helvetica. Custom font support is not yet available.
- **PDF/A-2b only** — higher conformance levels (PDF/A-2a with tagged structure, PDF/A-3 with attachments) are not supported.
