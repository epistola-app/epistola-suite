# Editor v2 Architecture

## Overview

Editor v2 is a framework-agnostic document template editor built with clear layer separation:

```
┌─────────────────────────────────────────────┐
│                   UI Layer                   │
│  (palette, sidebar, toolbar - DOM-based)    │
├─────────────────────────────────────────────┤
│                 DOM Layer                    │
│  (renderer, selection, drag-and-drop)       │
├─────────────────────────────────────────────┤
│                Core Layer                    │
│  (editor, state, history, commands)         │
├─────────────────────────────────────────────┤
│               Blocks Layer                   │
│  (registry, tree operations, definitions)   │
└─────────────────────────────────────────────┘
```

## Directory Structure

```
src/
├── core/              # Headless editor (no DOM dependencies)
│   ├── editor.ts      # Unified headless API (createEditor)
│   ├── state.ts       # Observable state management
│   ├── history.ts     # Undo/redo stack
│   ├── commands.ts    # Command pattern for mutations
│   └── persistence.ts # Save orchestration with debouncing
│
├── blocks/            # Block type system
│   ├── registry.ts    # Block type registration
│   ├── tree.ts        # Generic tree traversal/mutation
│   ├── types.ts       # Block definition types
│   └── definitions/   # Built-in block implementations
│       ├── text.ts
│       ├── columns.ts
│       ├── table.ts
│       └── ...
│
├── dom/               # DOM rendering layer
│   ├── renderer.ts    # Block tree → DOM rendering
│   ├── selection.ts   # Selection state & highlighting
│   └── dnd.ts         # Drag-and-drop handling
│
├── ui/                # UI components (DOM-dependent)
│   ├── palette.ts     # Block type picker
│   ├── sidebar.ts     # Block/document properties
│   ├── toolbar.ts     # Top action bar
│   └── inputs/        # Form input components
│
├── richtext/          # TipTap rich text integration
│   └── editor.ts      # Rich text editor wrapper
│
├── types/             # Shared type definitions
│   ├── template.ts    # Template, Block types
│   ├── styles.ts      # CSS style types
│   └── richtext.ts    # Rich text content types
│
└── index.ts           # Public API exports
```

## Layer Responsibilities

### Core Layer (Headless)

The core layer has **zero DOM dependencies** and can run in Node.js or any JavaScript environment.

- **editor.ts**: Main entry point for headless usage. Wraps state, history, and persistence into a unified API with `onChange` subscriptions.
- **state.ts**: Simple observable state with `subscribe()` for reactive updates.
- **history.ts**: Command-based undo/redo with configurable depth limit.
- **commands.ts**: Command pattern ensuring all mutations are undoable. Delegates tree operations to `blocks/tree.ts`.
- **persistence.ts**: Debounced auto-save with dirty state tracking.

### Blocks Layer

Provides a registry-based block type system:

- **registry.ts**: Central registry for block type definitions. Supports runtime registration.
- **tree.ts**: Generic tree operations (find, insert, update, remove, walk) that work with any registered block type.
- **types.ts**: TypeScript interfaces for block definitions.
- **definitions/**: Built-in block type implementations.

Key design: Tree operations use the registry to get children, avoiding hardcoded type checks.

### DOM Layer

Renders the block tree to DOM and handles user interactions:

- **renderer.ts**: Converts Template → DOM. Supports "edit" and "preview" modes.
- **selection.ts**: Manages selected block highlighting and keyboard navigation.
- **dnd.ts**: Handles drag-and-drop from palette and between blocks.

### UI Layer

Complete editor UI components:

- **palette.ts**: Draggable block type picker organized by category.
- **sidebar.ts**: Property editor for selected block or document styles.
- **toolbar.ts**: Undo/redo/save buttons with status display.

## Key Design Decisions

### 1. Registry-Based Block Types

Blocks are registered at runtime, not hardcoded:

```typescript
registerBlock({
  type: "my-block",
  getChildren: (block) => block.children,
  setChildren: (block, children) => ({ ...block, children }),
  // ...
});
```

Tree operations use the registry to traverse and mutate any block type generically.

### 2. Command Pattern for Mutations

All state changes go through commands:

```typescript
const command = new UpdateBlockCommand(blockId, updates);
const newTemplate = command.execute(template);
const restored = command.undo(newTemplate);
```

This enables:
- Built-in undo/redo
- Serializable operations for debugging
- Centralized validation

### 3. onChange for Preview

The headless API provides `onChange(listener)` instead of polling:

```typescript
editor.onChange((template, changeType) => {
  // Update preview on every change
  renderPreview(template);
});
```

Change types enable optimized updates (e.g., only re-render affected blocks).

### 4. Multi-Container Blocks

Some blocks (columns, tables) have multiple child containers. These use composite IDs:

```typescript
// Insert into column 1 of columns block
insertBlock(blocks, newBlock, "cols-123::col-1", 0);

// Insert into table cell
insertBlock(blocks, newBlock, "table-123::row-1::cell-1", 0);
```

The registry provides `getContainers()`, `getContainerChildren()`, and `setContainerChildren()` for these blocks.

## Data Flow

```
User Action (click, drag, type)
         │
         ▼
    UI Component
         │
         ▼
    executeCommand()
         │
         ▼
  ┌──────┴──────┐
  │  Command    │
  │  .execute() │
  └──────┬──────┘
         │
         ▼
   Tree Operations
   (blocks/tree.ts)
         │
         ▼
    New Template
         │
    ┌────┴────┐
    │         │
    ▼         ▼
 State    History
 Update    Push
    │         │
    └────┬────┘
         │
         ▼
  onChange listeners
  (preview update)
         │
         ▼
    DOM Renderer
    (re-render)
```

## Testing Strategy

Each layer has isolated tests:

- **core/**: Pure function tests, no DOM
- **blocks/**: Registry and tree operation tests
- **dom/**: JSDOM-based rendering tests
- **ui/**: Component behavior tests

Tests register mock block definitions in `beforeEach` to ensure isolation.
