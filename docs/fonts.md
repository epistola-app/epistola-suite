# Fonts

How Epistola resolves and renders fonts today, the known gaps, and the design
decisions waiting to be made before customer-uploaded fonts land.

> **TL;DR** — The PDF renderer currently ignores the `fontFamily` style entirely.
> Everything renders in **Helvetica** (standard PDF, non-embedded) or **Liberation
> Sans** (PDF/A, embedded). The editor preview, by contrast, honours the
> author's font choice via the browser. This divergence is the root of several
> open issues, including the `circle`/`square` bullet markers not appearing in
> standard PDFs ([#401](https://github.com/epistola-app/epistola-suite/issues/401)).

## Where fonts matter

Fonts surface on three independent rendering paths, and they do **not** agree:

| Surface             | Renderer                         | Honours author `fontFamily`? |
| ------------------- | -------------------------------- | ---------------------------- |
| PDF generation      | iText (`modules/generation`)     | **No** — fixed font          |
| Editor live preview | Browser (CSS) (`modules/editor`) | Yes — via CSS `font-family`  |
| PDF/A archival      | iText, embedded Liberation Sans  | **No** — fixed font          |

The editor exposes a **Font** select with six choices — System Default, Arial,
Georgia, Times New Roman, Courier New, Verdana — in
`modules/editor/src/main/typescript/engine/style-registry.ts` (the `fontFamily`
property, `inheritable: true`). The browser preview applies the chosen family;
the PDF output discards it. This is a genuine WYSIWYG divergence, not just a
missing feature.

## Bundled fonts

The repository bundles **Liberation Sans** (4 faces, ~410 KB each) under
`modules/generation/src/main/resources/fonts/`:

- `LiberationSans-Regular.ttf`, `-Bold.ttf`, `-Italic.ttf`, `-BoldItalic.ttf`
- License: `LICENSE-LiberationFonts` (SIL Open Font License)

Liberation Sans is metrically compatible with Helvetica/Arial, so line breaks
and layout are stable whether the run uses standard Helvetica or embedded
Liberation Sans. It also has a much wider glyph repertoire than the WinAnsi
encoding of standard Helvetica (see [Known gaps](#known-gaps)).

## Current rendering model

### Two modes, selected by PDF/A

`FontCache` (`modules/generation/.../pdf/FontCache.kt`) is constructed with a
`pdfaCompliant` flag and exposes `regular` / `bold` / `italic` / `boldItalic`:

| Mode               | Font            | Construction                                                              | Encoding             |
| ------------------ | --------------- | ------------------------------------------------------------------------- | -------------------- |
| Standard (default) | Helvetica       | `PdfFontFactory.createFont(StandardFonts.HELVETICA…)` — **not embedded**  | WinAnsi (CP1252)     |
| PDF/A              | Liberation Sans | `createFont(bytes, IDENTITY_H, FORCE_EMBEDDED)` — **embedded, subsetted** | Identity-H (Unicode) |

`DirectPdfRenderer` sets the document-wide default to `fontCache.regular`
(`DirectPdfRenderer.kt:365`). Document **preview is always standard
(non-PDF/A)** — PDF/A only applies to batch/API generation (see
[`pdfa.md`](pdfa.md)). So the Helvetica/WinAnsi path is the common case.

### Font selection is weight/style only — `fontFamily` is ignored

`fontFamily` is listed as an inheritable style and cascades correctly through
the style resolution chain, but it **never selects a font** in the PDF
renderer. `StyleApplicator.applyBlockStyles`
(`modules/generation/.../pdf/StyleApplicator.kt`, ~lines 205–220) only inspects
`fontWeight` / `fontStyle`:

- `fontWeight: bold` (or ≥ 700) → `fontCache.bold`
- `fontStyle: italic` → `fontCache.italic`
- otherwise → the document default (`fontCache.regular`)

Two consequences worth noting for future work:

1. **`fontFamily` is dead weight in the PDF path.** It is parsed, validated,
   inherited, and then dropped.
2. **Bold + italic collapses to bold.** The selection is `when { isBold ->
bold; isItalic -> italic }`, so a bold-italic run renders bold-only;
   `fontCache.boldItalic` is never reached from style resolution.

### List markers

Bullet markers come from `RenderingDefaults.bulletMarkers`
(`modules/generation/.../pdf/RenderingDefaults.kt`):

| Style    | Glyph | Codepoint |
| -------- | ----- | --------- |
| `disc`   | •     | U+2022    |
| `circle` | ○     | U+25CB    |
| `square` | ■     | U+25A0    |
| `dash`   | –     | U+2013    |

Both authoring paths now read this map — `ProseMirrorConverter` for rich-text
`bullet_list` (`ProseMirrorConverter.kt:182`) and `DataListNodeRenderer` for the
`datalist` component (`DataListNodeRenderer.kt:101`). Both pass the marker as a
**string**, so iText draws it in the list/document font — i.e. whatever
`fontCache.regular` resolves to. There is no dedicated symbol font.

## Known gaps

### 1. WinAnsi drops `circle` and `square` markers in standard PDFs ([#401])

Standard Helvetica is encoded as **WinAnsi (CP1252)**, a ~250-character Latin-1
set. Of the four markers:

- `•` (U+2022) and `–` (U+2013) **are** in WinAnsi → render correctly.
- `○` (U+25CB) and `■` (U+25A0) are **not** in WinAnsi → iText silently drops
  them, so those bullets render with **no marker at all**.

This only affects the **standard** path. In PDF/A mode the markers render fine,
because embedded Liberation Sans (Identity-H) has all four glyphs. Since preview
is always standard, this is what users see day to day. A reproduction confirmed
the split precisely: `disc`/`dash` render, `circle`/`square` do not.

This is fundamentally an **encoding** problem (the default font cannot represent
the codepoint), not a "wrong font for markers" problem.

### 2. `fontFamily` has no effect on PDFs

As above — the author picks a font in the editor, the preview honours it, the
PDF ignores it. `pdfa.md` already records this under Limitations ("Font family
is fixed"); it is **not** specific to PDF/A.

### 3. Bold + italic renders as bold

`fontCache.boldItalic` exists but is unreachable from `StyleApplicator`.

### 4. PDF/A size

`pdfa.md` reports PDF/A documents at ~400 KB attributed to embedded fonts.
Whether iText is effectively subsetting (it should, with `IDENTITY_H` +
`FORCE_EMBEDDED`) or embedding fuller faces is **unverified** and should be
measured before any decision that embeds fonts in the standard path — the
per-document cost there is the deciding factor.

## Design decisions pending (bullet marker font policy)

Recorded for when font work resumes. With customer-uploaded fonts on the
horizon, the marker should conceptually follow the element's resolved font
(matching CSS/Word and the editor preview), not a hardcoded one. Options
considered:

1. **Follow resolved font; make the standard default embedded Liberation
   Sans.** Fixes #401 and a whole class of "Helvetica can't encode X" bugs at
   once; markers and text both gain full Unicode. Cost: every standard
   (non-PDF/A) PDF grows and slows — rough estimate ~15–60 KB / ~10–30 ms, but
   see [gap #4](#4-pdfa-size); could be larger if subsetting is weak. Measure
   first.
2. **Follow resolved font; accept "missing = missing."** Keep Helvetica as the
   standard default. Future customer fonts naturally take over; if a font lacks
   a glyph it simply doesn't render. Clean philosophy, but #401 stays unfixed
   for the default output **by design** until a glyph-capable font is chosen.
3. **Follow resolved font; per-glyph fallback.** Use the resolved font; only
   when it cannot encode the marker glyph (`PdfFont.containsGlyph`) fall back to
   the bundled embedded font for that glyph. Zero cost on the common path,
   always renders, honours branded fonts when possible. Slightly more code
   (effective-font resolution + the glyph check).
4. **Hardcoded marker font.** Always draw markers in the bundled font
   regardless of the chosen text font. Rejected — markers wouldn't match a
   customer's branded font; inconsistent.

No option is implemented. The reported #401 symptom remains open, now with the
root cause documented here.

## Customer-uploaded fonts (roadmap)

Substantial work, sketched so the next person has the full picture:

- **Real `fontFamily` resolution** — map the cascaded family string to an actual
  embedded `PdfFont` in the renderer (the missing half of the existing
  inheritable style). Resolve the bold/bold-italic matrix properly while here.
- **Font storage & validation** — per-tenant font registry; validate uploads
  are well-formed TTF/OTF and **legally embeddable** (embedding-permission bits
  / licensing).
- **Subsetting & size** — embed only used glyphs; quantify the per-document
  cost (ties into [gap #4](#4-pdfa-size)).
- **PDF/A constraints** — every font must be embedded _and_ embeddable;
  uploaded fonts must satisfy PDF/A-2b or the template can't be PDF/A.
- **Editor preview parity** — the browser preview must load the same font
  (`@font-face`) so WYSIWYG holds; fallback chains must agree between browser
  and PDF.
- **Marker policy** — resolve the decision in the section above; with real
  custom fonts, option 3 (per-glyph fallback) becomes the most attractive.

## Editor preview parity

The in-editor preview renders fonts and list markers through the **browser**,
independent of the iText path. It honours `fontFamily` and shows `circle` /
`square` markers (CSS `list-style-type`) regardless of #401. Aligning the HTML
preview with the PDF output is tracked as a separate follow-up under
[#401](https://github.com/epistola-app/epistola-suite/issues/401).

## Key files

| File                                                          | Purpose                                               |
| ------------------------------------------------------------- | ----------------------------------------------------- |
| `modules/generation/.../pdf/FontCache.kt`                     | Dual font strategy (standard Helvetica vs embedded)   |
| `modules/generation/.../pdf/StyleApplicator.kt`               | Style → font; weight/style only, ignores `fontFamily` |
| `modules/generation/.../pdf/RenderingDefaults.kt`             | `bulletMarkers` map + `bulletMarker(style)`           |
| `modules/generation/.../pdf/DirectPdfRenderer.kt`             | Sets the document default font                        |
| `modules/generation/.../ProseMirrorConverter.kt`              | Rich-text `bullet_list` marker                        |
| `modules/generation/.../pdf/DataListNodeRenderer.kt`          | `datalist` component marker                           |
| `modules/generation/src/main/resources/fonts/`                | Bundled Liberation Sans TTF + license                 |
| `modules/editor/src/main/typescript/engine/style-registry.ts` | Editor `fontFamily` options                           |

## References

- [`pdfa.md`](pdfa.md) — PDF/A compliance, font strategy, limitations
- [`generation.md`](generation.md) — overall generation architecture
- [`rendering-upgrades.md`](rendering-upgrades.md) — versioned rendering defaults
- Issue [#401](https://github.com/epistola-app/epistola-suite/issues/401) — bullet
  marker rendering parity (PDF + preview)
