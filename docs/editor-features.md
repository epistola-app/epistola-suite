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

Line height is a unitless multiplier (e.g., `1.5` = 150% of font size). It's an inheritable property — setting it at the document or theme level applies to all text blocks. The default tenant theme sets `lineHeight: 1.5`.

**PDF rendering:** `TextNodeRenderer` resolves the style cascade and passes the value to `TipTapConverter` via a pre-resolved styles map. `TipTapConverter.applyTextStyles()` calls `paragraph.setMultipliedLeading(value)`.

### Table Cell Styling

Individual table cells can have per-cell styles: background color, text alignment, and per-side borders.

**Storage:** Cell styles are stored in `node.props.cellStyles` keyed by `"row-col"`:

```json
{ "cellStyles": { "0-0": { "backgroundColor": "#f0f0f0", "textAlign": "center" } } }
```

**Inspector:** When a cell is selected, the "Cell Style" section appears with background, text align, and border controls. Multi-cell selection applies the style to all selected cells.

**PDF rendering:** `TableNodeRenderer` applies per-cell styles via `StyleApplicator`. Table-level borders are skipped when a cell has its own borders. Default padding (8pt) is only applied when no per-cell padding is set. Table border color and width are overridable from node props.

### List Numbering and Bullet Styles

Ordered lists support multiple numbering formats, and bullet lists support multiple bullet styles. The **#** button in the bubble menu cycles through styles within the current list type.

**Ordered list formats:** decimal (1,2,3), lower-alpha (a,b,c), upper-alpha (A,B,C), lower-roman (i,ii,iii), upper-roman (I,II,III)

**Bullet list styles:** disc (•), circle (○), square (■), dash (–)

**PDF rendering:** `TipTapConverter` maps `listType` attribute to iText `ListNumberingType` for ordered lists, and `listStyle` attribute to custom `setListSymbol()` for bullet lists.

## Page Components

### Page Header / Footer

Headers and footers render on every page via iText page event handlers.

| Property        | Type    | Default | Description                      |
| --------------- | ------- | ------- | -------------------------------- |
| height          | unit    | 60pt    | Height of the header/footer band |
| hideOnFirstPage | boolean | false   | When true, hidden on page 1      |

**System parameters available:** `sys.pages.current` (page number), `sys.pages.total` (total pages, requires two-pass rendering).

### Address Block

A two-part layout for envelope window positioning. Consists of an **address** slot (rendered at absolute page coordinates) and an **aside** slot (rendered in the document flow).

**Architecture:**

```
┌─────────────────────────────────────────┐ ← page edge
│         (page margin)                   │
│   ┌─────────────────────────────────┐   │ ← content area top
│   │            [aside content       │   │ ← aside starts here (flow)
│   │             starts from top]    │   │
│   │                                 │   │
│ ┌─┤─────────┐                       │   │ ← address anchor (absolute)
│ │ │ Address  │                       │   │
│ │ │ content  │                       │   │
│ └─┤─────────┘                       │   │ ← address bottom
│   │                                 │   │
│   ├─────────────────────────────────┤   │ ← aside min-height end
│   │ [body content continues]        │   │
│   └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

**Properties:**

| Property     | Type   | Default      | Description                        |
| ------------ | ------ | ------------ | ---------------------------------- |
| standard     | select | din-c56-left | Envelope standard preset           |
| top          | number | 45           | Top position in mm from page edge  |
| left         | number | 20           | Left position in mm from page edge |
| addressWidth | number | 85           | Address window width in mm         |
| height       | number | 45           | Address window height in mm        |

**Presets:**

| Standard       | Top  | Left  | Width | Height |
| -------------- | ---- | ----- | ----- | ------ |
| DIN C5/6 Left  | 45mm | 20mm  | 85mm  | 45mm   |
| DIN C5/6 Right | 45mm | 105mm | 85mm  | 45mm   |

**Rendering:**

- **Address content** is rendered by `AddressBlockEventHandler` at absolute page coordinates using `PdfCanvas`. This guarantees exact positioning regardless of page margins. Renders on page 1 only.
- **Aside content** is rendered by `AddressBlockNodeRenderer` in the document flow as a `Div` with left margin matching the address width. Min-height ensures body content starts below the address bottom.
- `DirectPdfRenderer.hoistAddressBlock()` moves the address block to be the first child of root, ensuring aside content renders on page 1.

**Constraints:**

- One per document (`maxInstancesPerDocument: 1`)
- Can be nested in containers/stencils (found by type scan)
- The address block should be the first content element (after header). Content before it may overlap the address area.

### Data List

A block that loops over a data expression and renders items as a formatted list.

**Properties:**

| Property   | Type       | Default | Description                     |
| ---------- | ---------- | ------- | ------------------------------- |
| expression | expression | —       | Array data source               |
| itemAlias  | text       | item    | Variable name for current item  |
| indexAlias | text       | —       | Variable name for current index |
| listType   | select     | bullet  | List format                     |

**List types:** bullet, decimal, lower-alpha, upper-alpha, lower-roman, upper-roman, none

**PDF rendering:** `DataListNodeRenderer` evaluates the expression, iterates results, and renders each item's template as an iText `ListItem` inside a `List`.

## PDF Rendering

### Page Margins

Page margins are stored in millimeters in `PageSettings.margins` and converted to points (`1mm = 2.834645pt`) before passing to iText's `Document.setMargins()`.

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

### Hard Breaks

Hard breaks (Shift+Enter) split the content into separate `Paragraph` objects at each break point. Intermediate paragraphs have zero margin/padding and `spacingRatio(0)` for tight line spacing. Only the last paragraph gets the standard `paragraphMarginBottom`.
