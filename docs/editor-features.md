# Editor Features

This document describes the components, styling, and rendering features available in the Epistola template editor.

## Components

### Separator

A horizontal line for visually separating sections.

**Properties (inspector):**

| Property  | Type   | Default | Description                          |
| --------- | ------ | ------- | ------------------------------------ |
| thickness | pt     | 1pt     | Line thickness                       |
| width     | %      | 100%    | Line width, always centered          |
| color     | color  | #d1d5db | Line color (hex, rgb)                |
| style     | select | solid   | Line style: solid, dashed, or dotted |

**Styles:** margin only (no padding, background, or borders).

**PDF rendering:** `SeparatorNodeRenderer` renders a centered div with a top border using iText `SolidBorder`/`DashedBorder`/`DottedBorder`. Default styles (margins) are in `RenderingDefaults.V1.componentSpacing["separator"]`.

## Text Formatting

### Inline Marks

| Mark          | Toolbar | Shortcut | PDF Rendering                          |
| ------------- | ------- | -------- | -------------------------------------- |
| Bold          | B       | Ctrl+B   | Bold font variant                      |
| Italic        | I       | Ctrl+I   | Italic font variant                    |
| Underline     | U       | Ctrl+U   | `setUnderline()`                       |
| Strikethrough | S       | —        | `setLineThrough()`                     |
| Subscript     | X₂      | —        | `setTextRise(-3f)` + 75% font size     |
| Superscript   | X²      | —        | `setTextRise(5f)` + 75% font size      |
| Link          | —       | —        | Blue text with `PdfAction.createURI()` |
| Expression    | {{}}    | —        | Evaluates and renders as text          |

Subscript and superscript are mutually exclusive — applying one removes the other.

## Styling

### Per-Side Borders

Borders are configured independently per side (top, right, bottom, left) using the `border` compound property type. Each side has:

- **Width** (pt or sp)
- **Style** (solid, dashed, dotted)
- **Color** (hex picker + text input)

A **link toggle** switches between editing all sides at once (linked) or independently (unlinked). When linked, changing any property applies to all four sides.

**Storage format:** Per-side shorthand strings on the node's `styles` map:

```json
{
  "borderTop": "2pt solid #333333",
  "borderBottom": "1pt dashed #cccccc"
}
```

**Architecture:** The `<epistola-border-input>` Lit component manages linked/unlinked state internally. The `COMPOUND_STYLE_TYPES` registry provides generic read/write for compound properties (spacing and border), eliminating hardcoded type checks in the inspector.

### Page Flow

| Property     | Type    | Description                                               |
| ------------ | ------- | --------------------------------------------------------- |
| keepTogether | boolean | Prevents a block from being split across page boundaries  |
| keepWithNext | boolean | Prevents a page break between this block and the next one |

These are style properties available on all block components (text, container, columns, table, datatable, conditional, loop). They appear as checkboxes in the inspector's "Page Flow" section.

**PDF rendering:** Maps to iText's `setKeepTogether(true)` / `setKeepWithNext(true)` via `StyleApplicator`.

### Line Height

Line height is configured as a fixed value in pt or sp (not a multiplier). It's an inheritable property — setting it at the document or theme level applies to all text blocks.

**PDF rendering:** `TextNodeRenderer` resolves the style cascade and parses the value to points. The resolved value is passed to `TipTapConverter` via a pre-resolved styles map. `TipTapConverter.applyTextStyles()` calls `paragraph.setFixedLeading(pts)`.

## PDF Rendering Defaults

### Widow/Orphan Control

All paragraphs and headings have widow/orphan control enabled by default:

- **Orphans:** minimum 2 lines at the bottom of a page
- **Widows:** minimum 2 lines at the top of a page

This prevents single isolated lines from appearing alone at page boundaries. No editor UI — applied as sensible defaults in `TipTapConverter`.

### Component Default Spacing

All block components have a default bottom margin defined in `RenderingDefaults.V1.componentSpacing`:

| Component | marginBottom |
| --------- | ------------ |
| text      | 1.5sp (6pt)  |
| container | 1.5sp (6pt)  |
| columns   | 1.5sp (6pt)  |
| table     | 1.5sp (6pt)  |
| datatable | 1.5sp (6pt)  |
| image     | 1.5sp (6pt)  |
| qrcode    | 1.5sp (6pt)  |
| separator | 1.5sp (6pt)  |
