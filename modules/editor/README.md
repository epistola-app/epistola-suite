# Epistola Template Editor — Architecture

This document describes the inner workings of the visual template editor: its data structures, behavior, integration surface, and functional requirements. It is intended as a specification for understanding (or rewriting) the editor.

## Table of Contents

1. [Overview](#1-overview)
2. [Public API & Integration](#2-public-api--integration)
3. [Core Data Structures](#3-core-data-structures)
4. [State Management](#4-state-management)
5. [Block System](#5-block-system)
6. [Rich Text (TipTap)](#6-rich-text-tiptap)
7. [Expression Evaluation System](#7-expression-evaluation-system)
8. [Drag & Drop](#8-drag--drop)
9. [Style System](#9-style-system)
10. [PDF Preview](#10-pdf-preview)
11. [Table System](#11-table-system)
12. [Schema & Validation](#12-schema--validation)
13. [Utility Functions](#13-utility-functions)

---

## 1. Overview

The editor is a **visual template editor** for creating dynamic documents (invoices, letters, reports). Users compose templates using drag-and-drop blocks. Blocks can contain dynamic expressions (`{{customer.name}}`) evaluated against test data. The editor shows a live PDF preview of the rendered document.

### How it's embedded

The editor is built as an **ES module library** (`dist/template-editor.js` + `dist/template-editor.css`). It exposes a single `mountEditor()` function that renders into any DOM container. This design lets the Spring Boot host page mount and control the editor without framework coupling.

### Role within the application

The host application (Thymeleaf + HTMX) renders the page chrome and injects initial data as window globals. The editor takes ownership of a container element and handles all editing interactions. Backend communication flows through callbacks supplied at mount time — the editor never makes HTTP requests directly.

---

## 2. Public API & Integration

### Mount function

```
mountEditor(options: EditorOptions): EditorInstance
```

| EditorOptions field | Type | Description |
|---|---|---|
| `container` | `HTMLElement` | DOM element to mount into |
| `template` | `Template?` | Initial template to load |
| `dataExamples` | `DataExample[]?` | Named test data sets |
| `dataModel` | `JsonObject \| null?` | JSON Schema for validation |
| `themes` | `ThemeSummary[]?` | Available themes |
| `defaultTheme` | `ThemeSummary \| null?` | Parent template's inherited theme |
| `onSave` | `(template) => void \| Promise<void>` | Called when user saves |
| `onExampleSelected` | `(exampleId: string \| null) => void` | Called when user switches data example |

| EditorInstance method | Description |
|---|---|
| `unmount()` | Tear down editor, remove CSS scoping class |
| `getTemplate()` | Return current template state |
| `setTemplate(template)` | Replace the template |

### Initialization sequence

1. Add `template-editor-root` CSS class to container
2. If `template` provided → `setTemplate()` + `markAsSaved()` (so initial state is "clean")
3. If `dataExamples` provided → load into store, select first example
4. If `dataModel` provided → validate with Zod, set schema (or warn and skip if invalid)
5. If `themes` / `defaultTheme` provided → load into store
6. Render component tree into container
7. Return `EditorInstance` handle

### Backend communication (callbacks)

The editor itself makes **no HTTP requests**. The host Thymeleaf template wires up callbacks that fetch backend endpoints. This table documents the callbacks the host page currently provides:

| Callback | Backend endpoint | Purpose |
|---|---|---|
| `onSave` | `PUT /tenants/{id}/templates/{id}/variants/{id}/draft` | Save template draft |
| `onSaveDataExamples` | `PATCH /tenants/{id}/templates/{id}` | Bulk-save data examples |
| `onUpdateDataExample` | `PATCH /tenants/{id}/templates/{id}/data-examples/{id}` | Update one data example |
| `onDeleteDataExample` | `DELETE /tenants/{id}/templates/{id}/data-examples/{id}` | Delete one data example |
| `onSaveSchema` | `PATCH /tenants/{id}/templates/{id}` | Save JSON Schema |
| `onValidateSchema` | `POST /tenants/{id}/templates/{id}/validate-schema` | Validate schema + examples |
| `onExampleSelected` | _(none — UI notification only)_ | Notify parent of selection change |

### CSRF token handling

The host page injects a `getCsrfToken()` function on `window` that reads the `XSRF-TOKEN` cookie. Every backend request includes an `X-XSRF-TOKEN` header with this value.

### CSS isolation

The mount function adds the class `template-editor-root` to the container. This class applies:

- `all: initial` — reset all inherited styles
- `contain: layout style` — CSS containment boundary
- Re-established defaults (font, colors, sizing)
- All Tailwind utilities are scoped to descendants of this class

On unmount the class is removed, restoring the container to its original state.

### Import map (shared dependencies)

Heavy dependencies are **not bundled** into the editor. They are served as pre-built ES modules from the `vendor` module and mapped via the browser's import map mechanism:

| Category | Libraries |
|---|---|
| UI framework | react, react-dom, react/jsx-runtime |
| State | zustand, zustand/middleware/immer, immer |
| Drag & drop | @dnd-kit/core, @dnd-kit/sortable, @dnd-kit/utilities |
| UI primitives | @radix-ui/react-*, lucide-react, class-variance-authority, clsx, tailwind-merge |
| Utilities | uuid, zod, @floating-ui/dom, motion, react-resizable-panels, embla-carousel-react |

**Intentionally bundled** (not externalized): TipTap and CodeMirror — multiple instances of these libraries cause conflicts.

### Thymeleaf integration (window globals)

The host page sets these globals before mounting:

| Global | Type | Purpose |
|---|---|---|
| `window.TEMPLATE_MODEL` | `unknown` | Serialized template |
| `window.TEMPLATE_ID` | `string` | Current template ID |
| `window.TENANT_ID` | `string` | Current tenant ID |
| `window.VARIANT_ID` | `string` | Current variant ID |
| `window.DATA_EXAMPLES` | `DataExample[]` | Test data sets |
| `window.DATA_MODEL` | `JsonObject \| null` | JSON Schema |
| `window.THEMES` | `ThemeSummary[]` | Available themes |
| `window.DEFAULT_THEME` | `ThemeSummary \| null` | Inherited theme |
| `window.APP_VERSION` | `string` | Application version |
| `window.APP_NAME` | `string` | Application name |
| `window.getCsrfToken` | `() => string` | CSRF token reader |

Components access `TENANT_ID`, `TEMPLATE_ID`, and `VARIANT_ID` directly (e.g., for building the PDF preview URL).

---

## 3. Core Data Structures

### Template (root document)

| Field | Type | Description |
|---|---|---|
| `id` | `string` | Unique identifier |
| `name` | `string` | Human-readable name |
| `version` | `number` | Schema version |
| `themeId` | `string \| null?` | Selected theme (optional) |
| `pageSettings` | `PageSettings` | Page format, orientation, margins |
| `blocks` | `Block[]` | Ordered list of top-level blocks |
| `documentStyles` | `DocumentStyles` | Cascading typography + background |

### PageSettings

| Field | Type | Values |
|---|---|---|
| `format` | `string` | `"A4"`, `"Letter"`, `"Custom"` |
| `orientation` | `string` | `"portrait"`, `"landscape"` |
| `margins` | `object` | `{ top, right, bottom, left }` (numbers) |

### DocumentStyles

| Field | Type | Notes |
|---|---|---|
| `fontFamily` | `string?` | CSS font-family value |
| `fontSize` | `string?` | e.g. `"16px"` |
| `fontWeight` | `string?` | e.g. `"400"`, `"700"` |
| `color` | `string?` | CSS color value |
| `lineHeight` | `string?` | e.g. `"1.5em"` |
| `letterSpacing` | `string?` | e.g. `"0.5px"` |
| `textAlign` | `string?` | `"left"`, `"center"`, `"right"`, `"justify"` |
| `backgroundColor` | `string?` | Does **not** inherit to blocks |

### Block (discriminated union)

All blocks share a base shape (`id: string`, `type: string`, `styles?: CSSProperties`). The `type` field discriminates between 9 block types. See [Section 5: Block System](#5-block-system) for details.

### Expression

| Field | Type | Description |
|---|---|---|
| `raw` | `string` | Expression source text |
| `language` | `ExpressionLanguage?` | `"jsonata"` (default) or `"javascript"` |

### PreviewOverrides

Manual overrides for conditional/loop behavior in the preview:

| Field | Type | Values |
|---|---|---|
| `conditionals` | `Record<string, value>` | `"data"` (evaluate), `"show"`, `"hide"` |
| `loops` | `Record<string, value>` | `"data"` (evaluate) or a fixed iteration count |

### DataExample

| Field | Type | Description |
|---|---|---|
| `id` | `string` | Unique identifier (min 1 char) |
| `name` | `string` | Display name (min 1 char) |
| `data` | `JsonObject` | Test data for expression evaluation |

Validated at runtime with a Zod schema.

### ThemeSummary

| Field | Type | Description |
|---|---|---|
| `id` | `string` | Theme identifier |
| `name` | `string` | Display name |
| `description` | `string?` | Optional description |

<details>
<summary>TypeScript interface definitions</summary>

```typescript
interface Template {
  id: string;
  name: string;
  version: number;
  themeId?: string | null;
  pageSettings: PageSettings;
  blocks: Block[];
  documentStyles: DocumentStyles;
}

interface PageSettings {
  format: "A4" | "Letter" | "Custom";
  orientation: "portrait" | "landscape";
  margins: { top: number; right: number; bottom: number; left: number };
}

interface DocumentStyles {
  fontFamily?: string;
  fontSize?: string;
  fontWeight?: string;
  color?: string;
  lineHeight?: string;
  letterSpacing?: string;
  textAlign?: "left" | "center" | "right" | "justify";
  backgroundColor?: string;
}

type ExpressionLanguage = "jsonata" | "javascript";

interface Expression {
  raw: string;
  language?: ExpressionLanguage;
}

interface PreviewOverrides {
  conditionals: Record<string, "data" | "show" | "hide">;
  loops: Record<string, number | "data">;
}

interface DataExample {
  id: string;
  name: string;
  data: JsonObject;
}

interface ThemeSummary {
  id: string;
  name: string;
  description?: string;
}
```

</details>

### Schema types

See [Section 12: Schema & Validation](#12-schema--validation) for the full schema type system.

---

## 4. State Management

All editor state lives in a single store. The store uses Immer for immutable updates and Zundo for undo/redo history.

### State shape

| Field | Type | Tracked in undo? | Description |
|---|---|---|---|
| `template` | `Template` | Yes | The document being edited |
| `lastSavedTemplate` | `Template \| null` | No | Snapshot for dirty detection |
| `selectedBlockId` | `string \| null` | No | Currently selected block |
| `testData` | `Record<string, unknown>` | No | Active preview data |
| `previewOverrides` | `PreviewOverrides` | No | Manual conditional/loop overrides |
| `dataExamples` | `DataExample[]` | No | Named test data sets |
| `selectedDataExampleId` | `string \| null` | No | Active data example |
| `schema` | `JsonSchema \| null` | No | Data validation schema |
| `previewMode` | `"pdf"` | No | Preview mode (only PDF) |
| `themes` | `ThemeSummary[]` | No | Available themes |
| `defaultTheme` | `ThemeSummary \| null` | No | Inherited theme |

### Actions

#### Template mutations

| Action | Description |
|---|---|
| `setTemplate(template)` | Replace entire template |
| `updateBlock(id, updates)` | Merge partial updates into a block (recursive find) |
| `addBlock(block, parentId, index)` | Insert block at position within a parent (or root) |
| `moveBlock(id, newParentId, newIndex)` | Remove block from current location, insert at new location |
| `deleteBlock(id)` | Remove block (clears selection if it was selected) |
| `updateDocumentStyles(styles)` | Merge partial updates into `template.documentStyles` |
| `updatePageSettings(settings)` | Merge partial updates into `template.pageSettings` |
| `updateThemeId(themeId)` | Set `template.themeId` |

#### Selection

| Action | Description |
|---|---|
| `selectBlock(id \| null)` | Set the selected block (or deselect) |

#### Test data & preview

| Action | Description |
|---|---|
| `setTestData(data)` | Replace preview test data |
| `setPreviewOverride(type, id, value)` | Set a conditional/loop override |
| `setPreviewMode(mode)` | Switch preview mode |

#### Data examples (CRUD)

| Action | Description |
|---|---|
| `setDataExamples(examples)` | Replace all examples (auto-selects first if needed) |
| `selectDataExample(id \| null)` | Switch active example (loads its data into `testData`) |
| `addDataExample(example)` | Add example (Zod-validated, skips if invalid) |
| `updateDataExample(id, updates)` | Merge updates (syncs `testData` if active example changed) |
| `deleteDataExample(id)` | Remove example (auto-selects another or falls back to defaults) |

**Auto-selection logic**: When the selected example is deleted, or when examples are loaded and nothing is selected, the store automatically selects the first available example. When no examples remain, it falls back to a built-in default test data set.

#### Schema

| Action | Description |
|---|---|
| `setSchema(schema \| null)` | Set JSON Schema (Zod-validated at boundary, skips if invalid) |

#### Themes

| Action | Description |
|---|---|
| `setThemes(themes)` | Set available themes |
| `setDefaultTheme(theme \| null)` | Set inherited theme |

#### Save state

| Action | Description |
|---|---|
| `markAsSaved()` | Snapshot current template as "last saved" |

### Dirty detection

Compares `template` against `lastSavedTemplate` via `JSON.stringify()`. If `lastSavedTemplate` is null (no save yet), the editor is considered dirty.

### Undo/redo

| Setting | Value |
|---|---|
| History limit | 100 entries |
| Debounce | 500ms (rapid changes like dragging/typing are coalesced) |
| What's tracked | Only `template` (selection, test data, schema excluded) |

Exposed functions: `undo()`, `redo()`, `clearHistory()`, `useCanUndo()`, `useCanRedo()`.

---

## 5. Block System

### Block types

Every block has `id: string`, `type: string`, and optional `styles?: CSSProperties`.

| Type | Purpose | Children | Specific fields |
|---|---|---|---|
| `text` | Rich text content | None | `content: JSONContent` (TipTap format) |
| `container` | Group blocks together | `children: Block[]` | _(none)_ |
| `conditional` | Show/hide based on expression | `children: Block[]` | `condition: Expression`, `inverse?: boolean` |
| `loop` | Repeat for each array item | `children: Block[]` | `expression: Expression`, `itemAlias: string`, `indexAlias?: string` |
| `columns` | Side-by-side layout | Via `columns[].children` | `columns: Column[]`, `gap?: number` |
| `table` | Tabular data | Via `rows[].cells[].children` | `rows: TableRow[]`, `columnWidths?: number[]`, `borderStyle?` |
| `pagebreak` | Force page break | None | _(none)_ |
| `pageheader` | Repeated page header | `children: Block[]` | _(none)_ |
| `pagefooter` | Repeated page footer | `children: Block[]` | _(none)_ |

### Nesting behavior

Blocks form a tree. Six block types can contain children:

- **container, conditional, loop, pageheader, pagefooter**: Store children in a `children: Block[]` array.
- **columns**: Each `Column` has `{ id, size, children: Block[] }`. The `size` is a flexbox weight (e.g. 1, 2, 3).
- **table**: `TableRow[]` → `TableCell[]` → `Block[]`. See [Section 11: Table System](#11-table-system).

### Composite IDs for nested containers

When targeting a specific column or table cell, a composite ID encodes the path:

| Format | Example | Meaning |
|---|---|---|
| `blockId::columnId` | `"abc::def"` | Column `def` inside columns block `abc` |
| `blockId::rowId::cellId` | `"abc::ghi::jkl"` | Cell `jkl` in row `ghi` inside table `abc` |

The store's `addBlock` and `moveBlock` actions parse these composite IDs to locate the correct insertion point.

### Recursive block operations

`findAndUpdateBlock(blocks, id, updater)` traverses the block tree:

1. Iterates blocks with `reduce`
2. If `block.id === id` → apply `updater(block)`. If updater returns `null`, the block is removed.
3. Otherwise, recurse into:
   - `children` arrays (container, conditional, loop, pageheader, pagefooter)
   - `columns[].children` (columns block)
   - `rows[].cells[].children` (table block)
4. Returns a new array (immutable)

<details>
<summary>TypeScript interface definitions</summary>

```typescript
interface BaseBlock {
  id: string;
  type: string;
  styles?: CSSProperties;
}

interface ContainerBlock extends BaseBlock {
  type: "container";
  children: Block[];
}

interface TextBlock extends BaseBlock {
  type: "text";
  content: JSONContent;
}

interface ConditionalBlock extends BaseBlock {
  type: "conditional";
  condition: Expression;
  inverse?: boolean;
  children: Block[];
}

interface LoopBlock extends BaseBlock {
  type: "loop";
  expression: Expression;
  itemAlias: string;
  indexAlias?: string;
  children: Block[];
}

interface ColumnsBlock extends BaseBlock {
  type: "columns";
  columns: Column[];
  gap?: number;
}

interface Column {
  id: string;
  size: number;
  children: Block[];
}

interface TableBlock extends BaseBlock {
  type: "table";
  rows: TableRow[];
  columnWidths?: number[];
  borderStyle?: "none" | "all" | "horizontal" | "vertical";
}

interface TableRow {
  id: string;
  cells: TableCell[];
  isHeader?: boolean;
}

interface TableCell {
  id: string;
  children: Block[];
  colspan?: number;
  rowspan?: number;
  styles?: CSSProperties;
}

interface PageBreakBlock extends BaseBlock {
  type: "pagebreak";
}

interface PageHeaderBlock extends BaseBlock {
  type: "pageheader";
  children: Block[];
}

interface PageFooterBlock extends BaseBlock {
  type: "pagefooter";
  children: Block[];
}

type Block =
  | ContainerBlock
  | TextBlock
  | ConditionalBlock
  | LoopBlock
  | ColumnsBlock
  | TableBlock
  | PageBreakBlock
  | PageHeaderBlock
  | PageFooterBlock;
```

</details>

---

## 6. Rich Text (TipTap)

Text blocks use TipTap for rich text editing. Content is stored as TipTap's `JSONContent` format (a JSON tree representing the document).

### Content structure

```json
{
  "type": "doc",
  "content": [
    {
      "type": "paragraph",
      "content": [
        { "type": "text", "text": "Hello, " },
        { "type": "expression", "attrs": { "expression": "customer.name" } },
        { "type": "text", "text": "!" }
      ]
    }
  ]
}
```

### Extensions

| Extension | Source | Purpose |
|---|---|---|
| StarterKit | `@tiptap/starter-kit` | Paragraphs, headings, lists, bold, italic, strike, code, blockquote, hard break, horizontal rule |
| Underline | `@tiptap/extension-underline` | Underline formatting |
| ExpressionNode | Custom | Inline `{{expr}}` chips |

### ExpressionNode extension

A custom **inline, atomic** TipTap node that represents a dynamic expression.

| Attribute | Type | Description |
|---|---|---|
| `expression` | `string` | The expression source (e.g. `"customer.name"`) |
| `isNew` | `boolean` | Set to `true` on creation to auto-open the editor popover; not persisted to HTML |

**Input rule**: Typing `{{` triggers creation of an empty expression node with `isNew: true`, which automatically opens the expression editor popover.

**Display**: The node renders as a chip showing the evaluated result (or `[expression]` on error). Values longer than 30 characters are truncated.

**Expression editor popover**: A CodeMirror-based editor with:
- Language toggle (JSONata / JavaScript)
- Live validation with 50ms debounce (green border = success, red = error)
- Autocomplete from test data paths + scope variables
- Keyboard shortcuts: Cmd+Enter to save, Esc to cancel

### BubbleMenu toolbar

When text is selected, a floating toolbar appears with:

| Group | Tools |
|---|---|
| Formatting | Bold, Italic, Underline, Strikethrough |
| Lists | Bullet list, Numbered list |
| Headings | H1, H2, H3 |
| Expression | Insert expression |

### Content synchronization

- **Editor → Store**: On every TipTap `onUpdate` event, `updateBlock(id, { content: editor.getJSON() })` pushes the new content to the store.
- **Store → Editor**: A `useEffect` watches `block.content` for external changes (e.g. undo/redo). If `JSON.stringify(editor.getJSON()) !== JSON.stringify(block.content)`, it calls `editor.commands.setContent(block.content)` to sync. The string comparison prevents unnecessary updates.

---

## 7. Expression Evaluation System

### Evaluator interface

All evaluators implement a common interface:

| Member | Type | Description |
|---|---|---|
| `type` | `string` (readonly) | Unique identifier (`"direct"`, `"iframe"`, `"jsonata"`) |
| `name` | `string` (readonly) | Human-readable label for UI |
| `isSandboxed` | `boolean` (readonly) | Whether expressions can access browser APIs |
| `initialize()` | `Promise<void>` | Async setup (create iframe, load library, etc.) |
| `evaluate(expression, context)` | `Promise<EvaluationResult>` | Evaluate expression against context |
| `dispose()` | `void` | Clean up resources |

`EvaluationResult`: `{ success: boolean; value?: unknown; error?: string }`

### Evaluator implementations

| Evaluator | Sandboxed | Mechanism | Notes |
|---|---|---|---|
| **JsonataEvaluator** | Yes | JSONata library | Default. JSON-native query language. No browser API access. |
| **DirectEvaluator** | No | `new Function()` | Fast. Expressions can access window indirectly. |
| **IframeEvaluator** | Yes | Sandboxed iframe + `postMessage` | 1-second timeout per evaluation. Creates iframe with `allow-scripts`. Filters non-serializable context values. |

### Evaluator context provider

A provider manages evaluator lifecycle:

- Creates evaluator instances via factory function
- Tracks initialization state (`isReady`)
- **Fallback**: If initialization fails (and current type is not already JSONata), falls back to the JSONata evaluator
- Disposes old evaluator when switching types
- UI shows a status indicator (green = ready, amber = initializing)

**Language mapping**: `"jsonata"` → JsonataEvaluator, `"javascript"` → DirectEvaluator.

### Scope context

Loop blocks create **scope variables** that are available to child expressions:

| Field | Type | Description |
|---|---|---|
| `name` | `string` | Variable name (e.g. `"item"`) |
| `type` | `string` | `"loop-item"` or `"loop-index"` |
| `arrayPath` | `string` | Path to the array being iterated (e.g. `"orders"`) |

Scope is hierarchical — nested loop blocks merge their variables with the parent scope. For preview, `loop-item` resolves to the first array element and `loop-index` resolves to `0`.

### Expression autocomplete

The autocomplete system provides suggestions based on:

1. **Top-level data properties** from test data (boost = 5)
2. **Scope variables** from enclosing loops (boost = 10, highest priority)
3. **Nested object properties** after typing a dot (boost = 8)
4. **Type-aware methods**: After resolving the value at a path, suggests methods based on the value's type (string methods, array methods, number methods)

Features:
- Case-insensitive prefix matching
- Path resolution with method chaining (`customer.name.toLowerCase().split()`)
- Type inference from actual runtime values

---

## 8. Drag & Drop

### Architecture

The editor uses a DnD context with `closestCenter` collision detection. There are two kinds of draggable sources and several kinds of droppable targets.

### Draggable sources

| Source | ID pattern | Data |
|---|---|---|
| Block palette item | `palette-{type}` | `{ type: "palette", blockType, createBlock }` |
| Existing block | block's ID | `{ type: "block", block }` |

Each palette item carries a factory function (`createBlock`) that produces a new block instance with a fresh ID.

### Droppable targets

| Target | ID pattern | Data | Description |
|---|---|---|---|
| Canvas root | `canvas-root` | `{ parentId: null, index: blocks.length }` | Empty canvas area |
| Canvas append | `canvas-append` | `{ parentId: null, index: blocks.length }` | Drop zone after last block |
| Before block | `drop-before-{blockId}` | `{ parentId, index }` | Drop zone above a block |
| Container interior | `container-{blockId}` | `{ parentId: blockId, index: children.length }` | Inside a container |
| Column | `column-{blockId}-{columnId}` | `{ parentId: blockId, columnId }` | Inside a specific column |
| Table cell | `cell-{blockId}-{rowId}-{cellId}` | `{ parentId: "blockId::rowId::cellId" }` | Inside a table cell |
| Conditional then/else | varies | `{ parentId }` | Inside conditional branches |
| Loop interior | varies | `{ parentId: blockId }` | Inside a loop block |
| Page header/footer | varies | `{ parentId: blockId }` | Inside page header/footer |

### Drag overlay

While dragging, a floating overlay shows the block type name (e.g. "TEXT", "CONTAINER") in a semi-transparent badge that follows the cursor.

### Flow

1. **onDragStart**: If dragging from palette → create new block via factory, store as `activeBlock`. If dragging existing block → store it as `activeBlock`.
2. **onDragEnd**: Parse the droppable target's data to determine `targetParentId` and `targetIndex`. For column targets, build composite ID `blockId::columnId`. For cell targets, build `blockId::rowId::cellId`. Then:
   - **Palette source** → call `addBlock(newBlock, targetParentId, targetIndex)`
   - **Existing block** → call `moveBlock(blockId, targetParentId, targetIndex)`

---

## 9. Style System

### Two levels of styling

| Level | Stored as | Scope |
|---|---|---|
| Document styles | `template.documentStyles` (typed `DocumentStyles`) | Typography inherits to all blocks |
| Block styles | `block.styles` (typed `CSSProperties`) | Applies only to that block |

### Inheritable properties

Only these properties cascade from document styles to blocks. All other styles (spacing, borders, background, layout) do **not** inherit.

| Property |
|---|
| `fontFamily` |
| `fontSize` |
| `fontWeight` |
| `color` |
| `lineHeight` |
| `letterSpacing` |
| `textAlign` |

### Style merging logic (`mergeStyles`)

```
mergeStyles(documentStyles, blockStyles) → CSSProperties
```

1. If block has no styles → return only inheritable properties from document styles
2. If document has no styles → return block styles as-is
3. Otherwise → start with block styles, fill in missing inheritable properties from document styles (block values take precedence)

### CSS value parsing

| Function | Description |
|---|---|
| `parseValueWithUnit(value)` | Parse `"10px"` → `{ value: 10, unit: "px" }` (or null) |
| `formatValueWithUnit(value, unit)` | Format `10, "px"` → `"10px"` |

Supported units: `px`, `em`, `rem`, `%`, `pt`.

### Style constants

| Constant | Values |
|---|---|
| `FONT_FAMILIES` | System Default, Arial, Georgia, Times New Roman, Courier New, Verdana |
| `FONT_WEIGHTS` | Normal (400), Medium (500), Semi Bold (600), Bold (700) |
| `BORDER_STYLES` | None, Solid, Dashed, Dotted, Double |
| `DISPLAY_OPTIONS` | Block, Flex, None |
| `FLEX_DIRECTIONS` | Row, Row Reverse, Column, Column Reverse |
| `ALIGN_OPTIONS` | Start, Center, End, Stretch, Baseline |
| `JUSTIFY_OPTIONS` | Start, Center, End, Between, Around, Evenly |
| `TEXT_ALIGN_OPTIONS` | Left, Center, Right, Justify |

### Block style editor categories

The block style inspector is organized into collapsible sections:

| Section | Properties |
|---|---|
| Spacing | Padding (top/right/bottom/left), Margin (top/right/bottom/left) |
| Typography | Font family, size, weight, color, text align, line height, letter spacing |
| Background | Background color |
| Borders | Width, style, color, radius |
| Effects | Box shadow, opacity (0–100%) |
| Layout | Width, height, display; if flex: direction, gap, align items, justify content |

---

## 10. PDF Preview

The editor generates live PDF previews via the backend.

### Request flow

1. Template or test data changes in the store
2. After a **500ms debounce**, a POST request is sent
3. Any **in-flight request is aborted** (via `AbortController`)
4. Previous blob URL is revoked (memory cleanup)
5. Response blob is converted to an object URL
6. URL is displayed in an `<iframe>`

### Endpoint

```
POST /tenants/{tenantId}/templates/{templateId}/variants/{variantId}/preview
```

**Payload**: `{ templateModel: Template, data: JsonObject }`

**Headers**: `Content-Type: application/json`, `X-XSRF-TOKEN: {csrfToken}`

**Response**: PDF binary (displayed as blob URL in iframe)

### States

| State | UI |
|---|---|
| `idle` | Initial, nothing shown |
| `loading` | Spinner |
| `success` | PDF iframe |
| `error` | Error message + retry button |

### Cleanup

On unmount, any pending request is aborted and the last blob URL is revoked.

---

## 11. Table System

### Data structure

```
TableBlock
  └── rows: TableRow[]
        └── cells: TableCell[]
              └── children: Block[]   (arbitrary nested blocks)
```

Additional `TableBlock` fields:
- `columnWidths?: number[]` — ratio-based widths for each column
- `borderStyle?: "none" | "all" | "horizontal" | "vertical"`

Additional `TableRow` fields:
- `isHeader?: boolean` — renders as header cells

Additional `TableCell` fields:
- `colspan?: number`
- `rowspan?: number`
- `styles?: CSSProperties`

### Cell merging

**Merging** sets `colspan`/`rowspan` on the top-left cell and removes other cells from the selection. Children from all merged cells are consolidated into the surviving cell.

**Splitting** reverses a merge by creating individual cells to fill the `colspan × rowspan` area and resetting the original cell to `1×1`.

**Occupied cell tracking**: When rendering, the table tracks which grid positions are occupied by cells with `rowspan > 1` from previous rows, using a set of coordinates. Occupied cells are skipped during rendering.

### Selection system

The table config dialog supports:
- **Click**: Select single cell (replaces selection)
- **Ctrl/Cmd+Click**: Toggle cell in/out of selection
- Selected cells must form a rectangle for merge operations

### Validation

`canMergeCells()` verifies that selected cells form a valid rectangle before allowing a merge.

### Operations

| Operation | Description |
|---|---|
| Add row | Append row with cells matching column count |
| Remove row | Remove last row (minimum 1) |
| Add column | Add cell to every row, extend `columnWidths` |
| Remove column | Remove last cell from each row (minimum 1) |
| Merge cells | Combine rectangular selection into one cell |
| Split cell | Reverse a merge, restore individual cells |
| Toggle header | Toggle `isHeader` on rows containing selected cells |

### Border styles

| Value | Table borders | Cell borders |
|---|---|---|
| `"none"` | None | None |
| `"all"` | Full border | Full border (grid) |
| `"horizontal"` | Top + bottom | Bottom only |
| `"vertical"` | Left + right | Right only |

### Table config dialog

A modal with:
- **Visual grid designer** (left panel) — shows cells with selection highlighting
- **Size controls** — add/remove rows and columns
- **Cell actions** — merge, split, toggle header
- **Border controls** — border style selector with visual preview
- **Working copy pattern** — edits don't apply until the "Apply" button is clicked

---

## 12. Schema & Validation

### Visual schema

The editor uses a **VisualSchema** representation for user-friendly editing, and converts bidirectionally to standard **JSON Schema** for storage and backend validation.

#### VisualSchema

```
VisualSchema
  └── fields: SchemaField[]
```

#### SchemaField (discriminated union on `type`)

All fields share: `id: string`, `name: string`, `required: boolean`, `description?: string`.

| Variant | `type` | Extra fields |
|---|---|---|
| PrimitiveField | `"string"`, `"number"`, `"integer"`, `"boolean"` | _(none)_ |
| ArrayField | `"array"` | `arrayItemType: SchemaFieldType`, `nestedFields?: SchemaField[]` |
| ObjectField | `"object"` | `nestedFields?: SchemaField[]` |

#### JsonSchema

A subset of JSON Schema Draft-07 supported by the editor:

| Field | Type | Description |
|---|---|---|
| `$schema` | `string?` | Schema URI |
| `type` | `"object"` | Always `"object"` at root |
| `properties` | `Record<string, JsonSchemaProperty>?` | Property definitions |
| `required` | `string[]?` | Required property names |
| `additionalProperties` | `boolean?` | Allow extra properties |

`JsonSchemaProperty` is recursive: `{ type, description?, items?, properties?, required? }`.

<details>
<summary>TypeScript interface definitions</summary>

```typescript
type SchemaFieldType = "string" | "number" | "integer" | "boolean" | "array" | "object";

interface PrimitiveField {
  id: string;
  name: string;
  required: boolean;
  description?: string;
  type: "string" | "number" | "integer" | "boolean";
}

interface ArrayField {
  id: string;
  name: string;
  required: boolean;
  description?: string;
  type: "array";
  arrayItemType: SchemaFieldType;
  nestedFields?: SchemaField[];
}

interface ObjectField {
  id: string;
  name: string;
  required: boolean;
  description?: string;
  type: "object";
  nestedFields?: SchemaField[];
}

type SchemaField = PrimitiveField | ArrayField | ObjectField;

interface VisualSchema {
  fields: SchemaField[];
}

type JsonSchemaProperty = {
  type: SchemaFieldType | SchemaFieldType[];
  description?: string;
  items?: JsonSchemaProperty;
  properties?: Record<string, JsonSchemaProperty>;
  required?: string[];
};

type JsonSchema = {
  $schema?: string;
  type: "object";
  properties?: Record<string, JsonSchemaProperty>;
  required?: string[];
  additionalProperties?: boolean;
};
```

</details>

### Bidirectional conversion

| Direction | Function | Notes |
|---|---|---|
| Visual → JSON Schema | `visualSchemaToJsonSchema()` | Outputs Draft-07 with `additionalProperties: true` |
| JSON Schema → Visual | `jsonSchemaToVisualSchema()` | Generates UUIDs for field IDs |

### Schema generation from data

`generateSchemaFromData(data: JsonObject): VisualSchema`

Infers a schema from example data:
- All fields default to `required: false`
- Arrays use the first element to infer item type
- Numbers: `Number.isInteger()` distinguishes `integer` from `number`
- Null values default to `string` type

### Data validation

`validateDataAgainstSchema(data, schema): { valid, errors[] }`

Validates example data against a JSON Schema:
- Checks required fields exist
- Validates property types recursively
- For arrays: validates each item
- Integer satisfies number type
- Errors include JSONPath-style paths (e.g. `$.customer.age`)

### Impact analysis

Detects expressions in the template that don't match the schema:

| Function | Description |
|---|---|
| `analyzeSchemaImpact(schema, expressions)` | Find expressions with no matching schema path |
| `detectRemovedPaths(oldSchema, newSchema, expressions)` | Find expressions using paths removed between schema versions |
| `getExpressionCoverage(schema, expressions)` | Calculate coverage percentage |

Issue types: `"missing"`, `"removed"`, `"type_mismatch"`.

### Schema migration

`detectMigrations(schema, examples): { compatible, migrations[] }`

Detects and suggests fixes for data that doesn't match the schema:

| Issue type | Description | Auto-migratable? |
|---|---|---|
| `TYPE_MISMATCH` | Value has wrong type | Depends on types |
| `MISSING_REQUIRED` | Required field missing | No |
| `UNKNOWN_FIELD` | Field not in schema | No |

**Auto-conversion rules**:
- `number/boolean → string`: Always (`String(value)`)
- `string → number`: If parseable (`parseInt`/`parseFloat`)
- `string → boolean`: If `"true"/"1"/"yes"` or `"false"/"0"/"no"`
- `number → boolean`: `0 → false`, anything else → `true`
- Object/array conversions: Not auto-migratable

`applyMigration()` and `applyAllMigrations()` apply auto-migratable fixes to data examples.

---

## 13. Utility Functions

### Expression extraction

| Function | Description |
|---|---|
| `extractExpressions(blocks)` | Traverse all blocks recursively, return `Set<string>` of all expression paths |
| `extractFromTipTapContent(content, set)` | Walk TipTap JSON tree, find `expression` nodes |
| `extractPathsFromExpression(expr, set)` | Regex-based path extraction from expression strings |

The traversal covers all block types including columns (`columns[].children`) and tables (`rows[].cells[].children`).

### Path utilities

| Function | Description |
|---|---|
| `normalizeArrayPath(path)` | `items[0].price` → `items[].price` (replace numeric indices with `[]`) |
| `getRootPaths(expressions)` | Extract top-level keys (e.g. `customer.name` → `customer`) |
| `pathMatchesSchema(exprPath, schemaPaths)` | Check if an expression path matches any schema path (exact or prefix, with array notation) |

### Type inference (for autocomplete)

| Function | Description |
|---|---|
| `inferType(value)` | Runtime type inference → `{ kind: "primitive" \| "array" \| "object" \| "unknown", ... }` |
| `parsePath(pathStr)` | Parse `"customer.orders[0].name"` into segments |
| `resolvePathType(path, data, scopeVars)` | Full path resolution with method chaining support |
| `resolvePathValue(path, data, scopeVars)` | Get the actual value at a data path |

### Scope utilities

| Function | Description |
|---|---|
| `resolveScopeVariable(name, scopeVars, testData)` | Resolve a scope variable to its actual value (first array element for items, `0` for indices) |
| `buildEvaluationContext(data, scopeVars)` | Build evaluation context merging data + scope variables |
| `extractPaths(obj, prefix)` | Extract all nested paths from an object for autocomplete |
