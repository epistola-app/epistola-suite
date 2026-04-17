# Implementation Plan: Editor Improvements for Municipal Letters

## Branch: `feat/editor-improvements`

---

## 1. Per-Side Borders (UI)

**Problem:** Editor only offers unified border controls (all sides same). The PDF renderer already supports per-side borders via `StyleApplicator.BORDER_SIDE_SETTERS` — only the editor UI is missing.

**Implementation:**

**`modules/editor/src/main/typescript/engine/style-registry.ts`** — Add 4 new compound properties:

- `borderTop`, `borderRight`, `borderBottom`, `borderLeft`
- Each is a compound shorthand like `"2pt solid #000"` (matching what `StyleApplicator.parseBorderShorthand()` expects)
- Or: split into `borderTopWidth`, `borderTopStyle`, `borderTopColor` (etc.) as individual properties
- **Recommended:** Use the shorthand approach since the PDF renderer already parses `"2pt solid #000"` format via `BORDER_SIDE_SETTERS`

**`modules/editor/src/main/typescript/engine/registry.ts`** — Update `LAYOUT_STYLES` to include the new border properties.

**Inspector UI** — Create a border editor component (similar to the spacing editor's T/R/B/L grid) that lets users set width, style, and color per side. When all sides are the same, show a unified control; expand to per-side when needed.

**No PDF changes needed** — `StyleApplicator` lines 211-218 already handle `borderTop`/`borderRight`/`borderBottom`/`borderLeft` with `parseBorderShorthand()`.

---

## 2. Line Height in PDF

**Problem:** Style registry has `lineHeight`, editor shows the control, but `StyleApplicator.kt` line 225 skips it: _"lineHeight is handled differently in iText - skipping for now"_.

**Implementation:**

**`modules/generation/src/main/kotlin/app/epistola/generation/pdf/StyleApplicator.kt`** — Replace the comment at line 225 with:

```kotlin
"lineHeight" -> {
    val value = parseFloat(v)
    if (value != null && element is Paragraph) {
        element.setMultipliedLeading(value)
    }
}
```

iText's `setMultipliedLeading(float)` sets line height as a multiplier of font size (e.g., 1.5 = 150% line height), which matches the CSS `line-height` multiplier value stored in the style registry.

**Also apply in `TipTapConverter.kt`** — The `convertParagraph()` and `convertHeading()` methods create `Paragraph` objects. Pass resolved line height from document styles to these paragraphs.

---

## 3. Horizontal Rule (Separator)

**Problem:** No `<hr>` equivalent block type. Workaround (container with border-bottom) requires per-side borders which are also missing in UI.

**Implementation:**

### Editor side

**`modules/editor/src/main/typescript/engine/registry.ts`** — Register new component:

```typescript
{
  type: 'separator',
  label: 'Separator',
  icon: 'separator', // add icon to design-system icons.svg
  category: 'content',
  slots: [],  // no children
  allowedChildren: { mode: 'none' },
  applicableStyles: ['margin', 'borderColor', 'borderWidth', 'borderStyle'],
  defaultStyles: {
    borderBottomWidth: '1pt',
    borderBottomStyle: 'solid',
    borderBottomColor: '#cccccc',
    marginTop: '1.5sp',
    marginBottom: '1.5sp',
  },
  inspector: [],  // styles are sufficient
}
```

**`@epistola.app/editor-model`** — Add `'separator'` to the node type enum in the shared schema.

### PDF side

**`modules/generation/src/main/kotlin/app/epistola/generation/pdf/SeparatorNodeRenderer.kt`** — New renderer:

```kotlin
class SeparatorNodeRenderer : NodeRenderer {
    override fun render(...): List<IElement> {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(div, ...)
        // If no explicit border styles, apply default bottom border
        div.setHeight(0f)  // zero-height div with bottom border = horizontal line
        return listOf(div)
    }
}
```

**`DirectPdfRenderer.kt`** — Register: `"separator" → SeparatorNodeRenderer()`

---

## 4. Subscript / Superscript Marks

**Problem:** No `sub`/`sup` text formatting. Needed for m², footnote markers, legal references.

**Implementation:**

### Editor side

**`modules/editor/src/main/typescript/prosemirror/schema.ts`** — Add two marks:

```typescript
subscript: {
  parseDOM: [{ tag: 'sub' }],
  toDOM() { return ['sub', 0] },
},
superscript: {
  parseDOM: [{ tag: 'sup' }],
  toDOM() { return ['sup', 0] },
  excludes: 'subscript',  // mutually exclusive
},
```

**Bubble menu** — Add sub/sup toggle buttons to the text formatting toolbar.

### PDF side

**`modules/generation/src/main/kotlin/app/epistola/generation/TipTapConverter.kt`** — In `applyMarks()` (line 262+), add:

```kotlin
"subscript" -> {
    text.setTextRise(-3f)
    text.setFontSize(text.getProperty<UnitValue>(Property.FONT_SIZE)?.value?.times(0.75f) ?: 9f)
}
"superscript" -> {
    text.setTextRise(5f)
    text.setFontSize(text.getProperty<UnitValue>(Property.FONT_SIZE)?.value?.times(0.75f) ?: 9f)
}
```

`setTextRise()` shifts text up (positive) or down (negative). Font size reduced to 75% of parent.

---

## 5. Text Indent

**Problem:** No first-line paragraph indentation — common in formal Dutch letters.

**Implementation:**

**`modules/editor/src/main/typescript/engine/style-registry.ts`** — Add to typography properties:

```typescript
{ key: 'textIndent', label: 'First Line Indent', type: 'unit', units: ['pt', 'sp'], inheritable: true }
```

**`modules/generation/src/main/kotlin/app/epistola/generation/pdf/StyleApplicator.kt`** — Add case:

```kotlin
"textIndent" -> {
    val pts = parseSizeValue(v, baseFontSizePt, spacingUnit)
    if (pts != null && element is Paragraph) {
        element.setFirstLineIndent(pts)
    }
}
```

Also add `"textIndent"` to `INHERITABLE_KEYS` so it cascades from document/theme styles.

---

## 6. First-Page Header/Footer Variation

**Problem:** Headers/footers render identically on every page. Almost all letters need a different (or no) header on page 1.

**Implementation:**

### Editor side

**`modules/editor/src/main/typescript/engine/registry.ts`** — Add inspector field to `pageheader` and `pagefooter`:

```typescript
inspector: [{ key: "hideOnFirstPage", label: "Hide on first page", type: "boolean" }];
```

This is the simplest approach: a boolean that hides the header/footer on page 1. For different content on page 1, users can use a conditional expression with `sys.pages.current == 1` inside the header content.

### PDF side

**`modules/generation/src/main/kotlin/app/epistola/generation/pdf/PageHeaderEventHandler.kt`** — In the `handleEvent()` method, after getting the page number (line 56):

```kotlin
val pageNumber = pdfDoc.getPageNumber(page)
val hideOnFirstPage = document.nodes[headerNodeId]?.props?.get("hideOnFirstPage") == true
if (hideOnFirstPage && pageNumber == 1) return
```

**`PageFooterEventHandler.kt`** — Same pattern.

---

## 7. List Numbering Formats

**Problem:** Ordered lists hardcoded to `ListNumberingType.DECIMAL` in `TipTapConverter.kt` line 150. No letter or Roman numeral options.

**Implementation:**

### Editor side

**`modules/editor/src/main/typescript/prosemirror/schema.ts`** — Add attributes to `orderedList` node:

```typescript
orderedList: {
  attrs: {
    order: { default: 1 },         // start number (already exists in prosemirror-schema-list)
    listType: { default: 'decimal' }, // NEW: decimal, lower-alpha, upper-alpha, lower-roman, upper-roman
  },
}
```

**Bubble menu / inspector** — When an ordered list is selected, show a dropdown for numbering format and a number input for start value.

### PDF side

**`modules/generation/src/main/kotlin/app/epistola/generation/TipTapConverter.kt`** — In `convertOrderedList()`:

```kotlin
val listTypeStr = node["attrs"]?.get("listType") as? String ?: "decimal"
val startNumber = (node["attrs"]?.get("order") as? Number)?.toInt() ?: 1

val numberingType = when (listTypeStr) {
    "lower-alpha" -> ListNumberingType.ENGLISH_LOWER
    "upper-alpha" -> ListNumberingType.ENGLISH_UPPER
    "lower-roman" -> ListNumberingType.ROMAN_LOWER
    "upper-roman" -> ListNumberingType.ROMAN_UPPER
    else -> ListNumberingType.DECIMAL
}

val list = List(numberingType)
list.setItemStartIndex(startNumber)
```

---

## 8. Data List Component

**Problem:** The existing `loop` block repeats arbitrary blocks. There's no list-specific component that renders as a properly formatted bulleted/numbered list driven by data.

**Implementation:**

### Editor side

**`modules/editor/src/main/typescript/engine/registry.ts`** — Register new component:

```typescript
{
  type: 'datalist',
  label: 'Data List',
  icon: 'datalist',
  category: 'logic',
  slots: [{ name: 'item-template' }],  // template for each item
  allowedChildren: { mode: 'all' },
  applicableStyles: LAYOUT_STYLES,
  inspector: [
    { key: 'expression', label: 'Data Source', type: 'expression' },
    { key: 'itemAlias', label: 'Item Variable', type: 'text', default: 'item' },
    { key: 'indexAlias', label: 'Index Variable', type: 'text' },
    { key: 'listType', label: 'List Type', type: 'select', options: [
      { label: 'Bullet', value: 'bullet' },
      { label: 'Numbered', value: 'decimal' },
      { label: 'Letters (a, b, c)', value: 'lower-alpha' },
      { label: 'Roman (i, ii, iii)', value: 'lower-roman' },
      { label: 'No marker', value: 'none' },
    ]},
  ],
  defaultProps: { itemAlias: 'item', listType: 'bullet' },
}
```

### PDF side

**`modules/generation/src/main/kotlin/app/epistola/generation/pdf/DataListNodeRenderer.kt`** — New renderer that evaluates the expression, iterates over results, and renders each item's template as an iText `ListItem` inside a `List` with the configured numbering type.

**`DirectPdfRenderer.kt`** — Register: `"datalist" → DataListNodeRenderer()`

---

## 9. Table Cell Styling

**Problem:** Cell padding hardcoded (8pt), border colors hardcoded (#808080), no per-cell background/alignment/padding.

**Implementation:**

### Editor side

**Cell model extension** — Add optional `cellStyles` map to table node props, keyed by `"row-col"`:

```typescript
props: {
  cellStyles: {
    "0-0": { backgroundColor: "#f0f0f0", padding: "4pt", textAlign: "center" },
    "1-2": { backgroundColor: "#fff3cd" },
  }
}
```

**Cell inspector** — When a table cell is selected in the editor, show an inspector panel with background color, padding, text alignment, and per-side borders.

### PDF side

**`modules/generation/src/main/kotlin/app/epistola/generation/pdf/TableNodeRenderer.kt`** — Apply cell-specific styles from props when present, falling back to `RenderingDefaults` when not. Make `tableBorderColorHex` and `tableBorderWidth` overridable from node props.

---

## 10. Keep-Together / Keep-With-Next

**Problem:** No way to prevent page breaks from splitting a block (e.g., signature block, address block).

**Implementation:**

**`modules/editor/src/main/typescript/engine/style-registry.ts`** — Add boolean properties:

```typescript
{ key: 'keepTogether', label: 'Keep Together', type: 'boolean', inheritable: false },
{ key: 'keepWithNext', label: 'Keep With Next', type: 'boolean', inheritable: false },
```

**`modules/generation/src/main/kotlin/app/epistola/generation/pdf/StyleApplicator.kt`** — Map to iText's `setKeepTogether(true)` and `setKeepWithNext(true)`.

---

## 11. Widow/Orphan Control

**Problem:** No protection against single lines appearing alone at top/bottom of a page.

**Implementation:**

**`modules/generation/src/main/kotlin/app/epistola/generation/TipTapConverter.kt`** — Set sensible defaults:

```kotlin
paragraph.setOrphansControl(2)  // min 2 lines at bottom of page
paragraph.setWidowsControl(2)   // min 2 lines at top of page
```

No editor UI needed — just set globally as a rendering default.

---

## 12. Address Block Component

**Problem:** Municipal letters need the recipient address at exact coordinates for DIN C5/6 envelope windows.

**Implementation:**

### Editor side

New `addressblock` component with envelope standard presets (DIN C5/6 left/right window) and custom positioning. Max 1 per document.

**DIN C5/6 left window preset:** Top: 45mm, Left: 20mm, Width: 85mm, Height: 45mm

### PDF side

New `AddressBlockNodeRenderer` (returns empty in flow) + `AddressBlockEventHandler` (renders at absolute coordinates on page 1 via `PdfCanvas`).

---

## 13. SVG Image Support

**Problem:** Only raster images (PNG, JPEG) render in PDF.

**Implementation:**

**`modules/generation/src/main/kotlin/app/epistola/generation/pdf/ImageNodeRenderer.kt`** — Detect SVG content type or `<svg` prefix, use iText's `SvgConverter.convertToImage()` instead of `ImageDataFactory`.

**Asset upload** — Accept `image/svg+xml` content type.

---

## Implementation Order

1. Per-side borders — foundation for separator and table cell styling
2. Horizontal rule (separator) — uses per-side borders
3. Line height in PDF
4. Text indent
5. Subscript/superscript
6. Keep-together / keep-with-next
7. Widow/orphan control
8. List numbering formats
9. First-page header/footer
10. Data list component
11. Table cell styling
12. Address block
13. SVG image support

---

## Progress

| #   | Feature                  | Status      |
| --- | ------------------------ | ----------- |
| 1   | Per-side borders         | Not started |
| 2   | Horizontal rule          | Not started |
| 3   | Line height in PDF       | Not started |
| 4   | Text indent              | Not started |
| 5   | Subscript/superscript    | Not started |
| 6   | Keep-together            | Not started |
| 7   | Widow/orphan             | Not started |
| 8   | List numbering           | Not started |
| 9   | First-page header/footer | Not started |
| 10  | Data list                | Not started |
| 11  | Table cell styling       | Not started |
| 12  | Address block            | Not started |
| 13  | SVG images               | Not started |
