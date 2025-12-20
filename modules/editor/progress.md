# Custom Template Editor - Progress

## Overview

Building a custom template editor for PDF letters with:
- Two-column layout: editor (left) + live PDF preview (right)
- Block-based editor with drag & drop
- Expression system with placeholders showing test data values
- Context-aware autocomplete for expressions

## Completed

### Phase 1: Foundation
- [x] Project setup (Vite + React + TypeScript + Tailwind v4)
- [x] TypeScript types for template model (`src/types/template.ts`)
- [x] Basic editor layout with two columns
- [x] Zustand store with immer for state management (`src/store/editorStore.ts`)

### Phase 2: Block System
- [x] Block palette with draggable block types
- [x] Canvas with drop zones for blocks
- [x] Container block with nested children support
- [x] Text block with Tiptap rich text editor
- [x] Conditional (IF) block with preview override controls
- [x] Loop (EACH) block with array iteration

### Phase 3: Drag & Drop
- [x] dnd-kit integration for drag & drop
- [x] Block reordering within containers
- [x] Nested block support (blocks inside containers, conditionals, loops)
- [x] Drop zone indicators

### Phase 4: Expression System
- [x] Custom Tiptap Node for inline expressions (`ExpressionNode.tsx`)
- [x] Expression chips showing evaluated values from test data
- [x] Input rule: typing `{{expression}}` creates expression node
- [x] Click-to-edit expression chips
- [x] ExpressionEditor component with:
  - [x] Autocomplete dropdown from test data paths
  - [x] Type badges (array, string, number, etc.)
  - [x] Validation indicator (green/red border)
  - [x] Preview of evaluated value
  - [x] Keyboard navigation (arrows, tab, enter, escape)

### Phase 5: Loop Block Enhancements
- [x] ExpressionEditor for array path selection (filterArraysOnly mode)
- [x] Editable item alias variable name
- [x] Validation for valid JavaScript variable names
- [x] Preview count override (number input + reset button)
- [x] Display actual array length from test data

### Phase 6: Scope Context
- [x] ScopeContext for tracking variables in scope (`src/context/ScopeContext.tsx`)
- [x] ScopeProvider wraps loop children with scope variables
- [x] ExpressionEditor reads scope and adds variables to autocomplete
- [x] Loop item variables show with "(loop item)" type badge
- [x] Loop item properties extracted and prefixed with variable name
- [x] Nested loops: scope variables merge from parent contexts

## Recently Completed

### Expression Validation with Scope
- [x] Update validation logic to recognize scope variables as valid paths
- [x] Preview values resolve scope variables (shows first item from array)
- [x] Validation shows green checkmark for scope variable paths (e.g., `item.name`)

## Pending

### Preview Integration
- [ ] Template → HTML renderer
- [ ] Puppeteer API endpoint integration
- [ ] Live PDF preview with debouncing
- [ ] PDF viewer component

### Complex Blocks
- [ ] Table block with expression-aware cells
- [ ] Image block with expression URLs
- [ ] Row/Column layout blocks

### Polish & Features
- [ ] Undo/redo
- [ ] Properties panel for selected blocks
- [ ] Template validation
- [ ] Export/import templates

## Architecture

```
src/
├── components/
│   ├── blocks/
│   │   ├── BlockRenderer.tsx      # Routes block types to components
│   │   ├── TextBlock.tsx          # Tiptap rich text editor
│   │   ├── ContainerBlock.tsx     # Generic container
│   │   ├── ConditionalBlock.tsx   # IF block with override controls
│   │   ├── LoopBlock.tsx          # EACH block with array iteration
│   │   ├── ExpressionNode.tsx     # Tiptap custom node for expressions
│   │   └── ExpressionEditor.tsx   # Autocomplete expression editor
│   ├── editor/
│   │   ├── BlockPalette.tsx       # Draggable block types
│   │   └── Canvas.tsx             # Drop zone for blocks
│   └── layout/
│       └── EditorLayout.tsx       # Two-column layout
├── context/
│   └── ScopeContext.tsx           # React context for scope variables
├── store/
│   └── editorStore.ts             # Zustand store with immer
├── types/
│   └── template.ts                # TypeScript type definitions
└── App.tsx                        # Main app component
```

## Tech Stack

| Layer | Technology |
|-------|------------|
| Framework | React + TypeScript |
| Build | Vite |
| Rich Text | Tiptap (ProseMirror) |
| Drag & Drop | dnd-kit |
| State | Zustand + immer |
| Styling | Tailwind CSS v4 |

## Test Data Structure

```json
{
  "customer": {
    "name": "Acme Corp",
    "email": "contact@acme.com",
    "address": {
      "street": "123 Main St",
      "city": "Springfield"
    }
  },
  "items": [
    { "name": "Widget A", "price": 29.99, "quantity": 2 },
    { "name": "Widget B", "price": 49.99, "quantity": 1 }
  ],
  "total": 109.97,
  "date": "2024-01-15"
}
```

## Expression Examples

| Expression | Result |
|------------|--------|
| `customer.name` | Acme Corp |
| `customer.address.city` | Springfield |
| `items` | (array of 2 items) |
| `item.name` (in loop) | Widget A |
| `item.price` (in loop) | 29.99 |
