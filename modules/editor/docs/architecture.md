# Template Editor Architecture

A custom block-based template editor for generating PDF letters with dynamic content.

## Overview

The editor allows users to create document templates with:
- Rich text formatting (headings, lists, bold, italic, etc.)
- Dynamic expressions that evaluate against JSON data
- Conditional blocks (IF/IF NOT)
- Loop blocks for repeating content
- Live preview rendering

## Tech Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| Framework | React 18 + TypeScript | UI components and type safety |
| Build | Vite | Fast development and bundling |
| Rich Text | Tiptap (ProseMirror) | Text editing with custom extensions |
| Drag & Drop | dnd-kit | Block reordering and nesting |
| State | Zustand + Immer | Immutable state management |
| Styling | Tailwind CSS v4 | Utility-first CSS |

## Directory Structure

```
src/
├── components/
│   ├── blocks/                    # Block components
│   │   ├── BlockRenderer.tsx      # Routes block types to components
│   │   ├── TextBlock.tsx          # Rich text with Tiptap
│   │   ├── ContainerBlock.tsx     # Generic container for grouping
│   │   ├── ConditionalBlock.tsx   # IF/IF NOT logic
│   │   ├── LoopBlock.tsx          # Array iteration
│   │   ├── ColumnsBlock.tsx       # Multi-column layout
│   │   ├── TableBlock.tsx         # Structured table
│   │   ├── TableConfigPopup.tsx   # Table configuration modal
│   │   ├── table-config/          # Table configuration components
│   │   │   ├── TableGridDesigner.tsx      # Visual grid UI
│   │   │   ├── TableSizeControls.tsx      # Row/column controls
│   │   │   ├── CellActionsControls.tsx    # Merge/split/header
│   │   │   ├── BorderControls.tsx         # Border style selector
│   │   │   ├── tableConfigUtils.ts        # Pure functions
│   │   │   └── useGridSelection.ts        # Selection hook
│   │   ├── ExpressionNode.tsx     # Tiptap extension for inline expressions
│   │   └── ExpressionEditor.tsx   # Autocomplete expression input
│   ├── ui/                        # Reusable UI components
│   │   └── Modal.tsx              # Modal dialog component
│   ├── editor/
│   │   ├── EditorLayout.tsx       # Two-column layout
│   │   ├── EditorProvider.tsx     # DnD context provider
│   │   ├── BlockPalette.tsx       # Draggable block types
│   │   ├── Canvas.tsx             # Drop zone for blocks
│   │   ├── Preview.tsx            # Live HTML preview renderer
│   │   └── PropertiesPanel.tsx    # Selected block properties
│   └── layout/
│       └── EditorLayout.tsx       # Main layout wrapper
├── context/
│   └── ScopeContext.tsx           # Variable scope for loops
├── store/
│   └── editorStore.ts             # Zustand store
├── types/
│   └── template.ts                # TypeScript type definitions
├── App.tsx                        # Root component
├── main.tsx                       # Entry point
└── index.css                      # Global styles
```

## Core Concepts

### 1. Block System

Blocks are the fundamental building units. Each block has:
- `id`: Unique identifier
- `type`: Block type discriminator
- `children`: Nested blocks (for containers)

**Block Types:**

| Type | Description | File |
|------|-------------|------|
| `text` | Rich text with expressions | `TextBlock.tsx` |
| `container` | Groups child blocks | `ContainerBlock.tsx` |
| `conditional` | Shows content based on condition | `ConditionalBlock.tsx` |
| `loop` | Repeats content for each array item | `LoopBlock.tsx` |
| `columns` | Multi-column layout with ratio-based sizing | `ColumnsBlock.tsx` |
| `table` | Structured table with configurable rows, columns, and cells | `TableBlock.tsx` |

### 2. Expression System

Expressions allow dynamic content from JSON data.

**Syntax:**
```javascript
// Simple path
customer.name

// Literals
"Hello World"
123
true

// JavaScript expressions
items.length
total * 1.1
customer.type === 'premium'
items.map(i => i.name).join(', ')
```

**Components:**

- `ExpressionNode.tsx`: Tiptap node extension that renders expressions as inline chips
- `ExpressionEditor.tsx`: Input with autocomplete, validation, and preview

**How it works:**
1. User types `{{` in a text block
2. Input rule triggers, creating an empty expression node
3. Expression editor opens with autocomplete from test data
4. User selects/types expression and saves
5. Chip displays evaluated value from test data

### 3. Scope Context

Loop blocks create scope variables accessible to nested blocks.

```tsx
// ScopeContext.tsx
interface ScopeVariable {
  name: string;           // e.g., "item"
  type: 'loop-item' | 'loop-index';
  arrayPath: string;      // e.g., "items"
}
```

When a loop block iterates over `items` as `item`:
- Child blocks can access `item`, `item.name`, `item.price`, etc.
- Autocomplete shows these scoped variables
- Preview resolves them from the first array element

### 4. State Management

Zustand store (`editorStore.ts`) manages:

```typescript
interface EditorStore {
  template: Template;           // Document structure
  selectedBlockId: string | null;
  testData: Record<string, unknown>;  // JSON for preview
  previewOverrides: PreviewOverrides;

  // Actions
  updateBlock: (id, updates) => void;
  addBlock: (block, parentId, index) => void;
  moveBlock: (id, newParentId, index) => void;
  deleteBlock: (id) => void;
  selectBlock: (id) => void;
}
```

### 5. Preview Rendering

`Preview.tsx` converts the template to HTML:

1. **Template traversal**: Recursively processes blocks
2. **Expression evaluation**: Uses `new Function()` for JS execution
3. **Context passing**: Loop variables added to context for children
4. **HTML generation**: Converts Tiptap JSON to HTML string

```typescript
function evaluateExpression(expr: string, context: Record<string, unknown>): unknown {
  const keys = Object.keys(context);
  const values = Object.values(context);
  const fn = new Function(...keys, `return ${expr}`);
  return fn(...values);
}
```

## Features

### Text Block
- Rich text editing via Tiptap
- Formatting toolbar (when selected): Bold, Italic, Underline, Strike, Lists, Headings
- Inline expressions via `{{` trigger
- Keyboard shortcuts (Ctrl+B, Ctrl+I, etc.)

### Conditional Block
- IF/IF NOT logic toggle
- Any JavaScript expression as condition
- Collapsed view shows minimal header
- Expanded view shows full controls

### Loop Block
- Iterates over arrays from test data
- Customizable item alias (default: `item`)
- Preview count override for testing
- Scoped variables available in children

### Columns Block
- Creates multi-column layouts for side-by-side content
- Each column can contain any blocks (text, containers, loops, etc.)
- Ratio-based sizing using flexbox (e.g., sizes [1, 2, 1] creates 25%-50%-25%)
- Add/remove columns dynamically
- Adjust column ratios when selected
- Gap between columns configurable

### Table Block
- Structured table with configurable grid structure
- Each cell is a droppable zone for any blocks
- **Configuration Popup** - Clean modal interface for table structure management:
  - Visual grid designer with selectable cells
  - Add/remove rows and columns
  - Merge cells (rectangular selection)
  - Split merged cells back to individual cells
  - Toggle header rows
  - Border style options (none, all, horizontal, vertical)
- Cell merging via colspan/rowspan
- Apply/Cancel workflow for safe configuration changes
- Nested block IDs use composite format: `blockId::rowId::cellId`

**Table Configuration Architecture:**
```
TableConfigPopup (main orchestrator)
├── Modal (reusable component)
├── TableGridDesigner (visual grid with cell selection)
├── TableSizeControls (add/remove rows/columns)
├── CellActionsControls (merge/split/header actions)
├── BorderControls (border style selection)
└── useGridSelection (hook for managing cell selection)

Pure utility functions (tableConfigUtils.ts):
├── addRow / removeRow
├── addColumn / removeColumn
├── mergeCells (validates rectangular selection)
├── splitCell (resets colspan/rowspan)
├── toggleRowHeader
└── canMergeCells (validation logic)
```

### Expression Editor
- Autocomplete from test data paths
- Scope-aware suggestions (loop variables)
- Real-time validation using `new Function()`
- Preview of evaluated value
- Type badges (array, string, object, loop item)

## Data Flow

```
┌─────────────────┐     ┌──────────────┐     ┌─────────────┐
│   User Input    │────▶│ Zustand Store │────▶│   Preview   │
│  (edit block)   │     │  (template)   │     │  (render)   │
└─────────────────┘     └──────────────┘     └─────────────┘
                               │
                               ▼
                        ┌──────────────┐
                        │  Block Tree  │
                        │  (re-render) │
                        └──────────────┘
```

## Composite IDs for Nested Containers

Columns and table blocks require nested droppable zones. To support this, we use composite IDs with `::` as a separator:

**Format:**
- Columns: `blockId::columnId`
- Table cells: `blockId::rowId::cellId`

**Why not use `-` separator?**
UUIDs contain hyphens, so using `-` would cause false positives when checking for composite IDs. The `::` separator is guaranteed not to appear in UUIDs.

**How it works:**
1. Drop zones provide composite `parentId` in their data
2. `EditorProvider` checks `parentId.includes('::')`
3. If composite, splits the ID and traverses the block tree to find the nested container
4. Inserts the new block at the correct depth

**Example:**
```typescript
// Dropping into a table cell
parentId: "table-abc::row-xyz::cell-123"
// EditorProvider splits this and finds:
// 1. Block with id "table-abc"
// 2. Row with id "row-xyz"
// 3. Cell with id "cell-123"
// 4. Adds block to cell.children
```

## Expression Evaluator System

The editor supports multiple expression evaluation strategies through a pluggable evaluator system. All evaluation is async for a consistent API across evaluators.

### Available Evaluators

| Evaluator | Type | Speed | Security | Use Case |
|-----------|------|-------|----------|----------|
| DirectEvaluator | `direct` | Fast | Low | Development, trusted users |
| IframeEvaluator | `iframe` | Slower | High | Untrusted expressions |

### Architecture

```
src/services/expression/
├── types.ts           # ExpressionEvaluator interface
├── DirectEvaluator.ts # new Function() implementation
├── IframeEvaluator.ts # Sandboxed iframe implementation
└── index.ts           # Exports

src/context/
└── EvaluatorContext.tsx  # React context for evaluator access
```

### DirectEvaluator

- Uses `new Function()` for JavaScript evaluation
- Fast execution (internally sync, exposed as async)
- **Not sandboxed** - expressions can access window indirectly

### IframeEvaluator

- Creates a sandboxed iframe with `sandbox="allow-scripts allow-same-origin"`
- Communication via `postMessage`
- 1 second timeout for infinite loop protection
- Blocked globals: `fetch`, `localStorage`, `document`, `parent`, etc.
- Note: `allow-same-origin` is needed for `postMessage` to work reliably

### Usage

```typescript
// In components
const { evaluate, isReady, type, setType } = useEvaluator();

// Async evaluation (all evaluators)
const result = await evaluate('customer.name', context);

// Switch evaluators
setType('iframe'); // or 'direct'
```

### Switching Evaluators

The header contains a dropdown to switch between evaluators at runtime:
- **Direct (Fast)** - For development and trusted users
- **Iframe (Sandboxed)** - For untrusted expressions

A green dot indicates the evaluator is ready. A "Secure" badge appears when using a sandboxed evaluator.

## Known Issues

### 1. Async Evaluation UI
- All evaluation is async, so expression chips and editor may briefly show "[...]" while evaluating
- With debouncing, DirectEvaluator feels nearly instant

### 2. Large Document Performance
- No virtualization for long documents
- All blocks render even if off-screen
- May slow down with 100+ blocks

### 3. Tiptap Node View Focus
- Expression editor needs 10ms delay to steal focus from Tiptap
- Occasional focus issues when creating expressions rapidly

### 4. Drag & Drop Edge Cases
- Dropping into deeply nested containers can be finicky
- Drop zone indicators could be more prominent

### 5. Preview Fidelity
- Preview uses inline styles, not actual PDF renderer
- Final PDF (via Puppeteer) may have slight differences
- No page break handling yet

## Potential Improvements

### High Priority

1. ~~**Sandboxed Expression Evaluation**~~ ✓ Implemented
   - ~~Integrate QuickJS-WASM for safe JavaScript execution~~ → Using iframe sandbox instead
   - Add helper functions (formatDate, formatCurrency, etc.) → TODO
   - ~~Execution timeout for infinite loop protection~~ ✓ 1 second timeout

2. **Undo/Redo**
   - Add history to Zustand store
   - Track block changes for reversal
   - Keyboard shortcuts (Ctrl+Z, Ctrl+Y)

3. **Template Persistence**
   - Save/load templates to backend
   - Auto-save functionality
   - Version history

### Medium Priority

4. ~~**Table Block**~~ ✓ Implemented
   - ~~Structured table with configurable rows/columns~~ ✓
   - ~~Expression-aware cells (each cell can contain any blocks)~~ ✓
   - ~~Cell merging (colspan/rowspan)~~ ✓
   - ~~Configuration popup with visual grid designer~~ ✓
   - ~~Header row support~~ ✓
   - ~~Border style options~~ ✓
   - TODO: Dynamic rows from arrays (loop integration)
   - TODO: Column width resizing

5. **Image Block**
   - URL input with expression support
   - Upload capability
   - Size/alignment controls

6. **Better DnD Experience**
   - Visual guides for nesting depth
   - Block type restrictions (e.g., no loop inside loop)
   - Copy/paste blocks

7. **Data Schema**
   - Define expected data structure
   - Generate TypeScript types for autocomplete
   - Validate test data against schema

### Nice to Have

8. **Collaboration**
   - Real-time multi-user editing
   - Presence indicators
   - Conflict resolution

9. **Template Library**
   - Pre-built templates
   - Block snippets
   - Import/export

10. **Advanced Formatting**
    - Text alignment
    - Font size/color
    - Background colors
    - Borders

11. **PDF Integration**
    - Live Puppeteer preview
    - Page break controls
    - Header/footer templates
    - Page numbers

## Testing

Currently no automated tests. Recommended additions:

1. **Unit Tests**
   - Expression evaluation
   - Scope resolution
   - Block operations (add, move, delete)

2. **Component Tests**
   - Block rendering
   - Toolbar interactions
   - Expression editor autocomplete

3. **E2E Tests**
   - Full editor workflow
   - Drag and drop
   - Preview accuracy

## Configuration

### Test Data

Edit `editorStore.ts` to change default test data:

```typescript
testData: {
  customer: {
    name: 'Acme Corp',
    email: 'contact@acme.com',
    // ...
  },
  items: [
    { name: 'Widget A', price: 29.99 },
    // ...
  ],
}
```

### Page Settings

Template includes page settings (not yet exposed in UI):

```typescript
pageSettings: {
  format: 'A4',
  orientation: 'portrait',
  margins: { top: 20, right: 20, bottom: 20, left: 20 },
}
```

## Contributing

1. Keep components focused and small
2. Use TypeScript strictly (no `any`)
3. Follow existing patterns for new block types
4. Update this documentation for significant changes
