# @epistola/headless-editor

Framework-agnostic editor core for template/block editing.

- No DOM/UI dependencies
- TypeScript-first API
- Works with any UI layer (vanilla, React, etc.)
- Provides block operations, validation, style cascade resolution, drag-drop constraints, expression helpers, undo/redo

## Install

```bash
pnpm add @epistola/headless-editor
```

## Quick Start

```ts
import { TemplateEditor } from "@epistola/headless-editor";

const editor = new TemplateEditor({
  template: {
    id: "tmpl-1",
    name: "My Template",
    blocks: [],
  },
});

const text = editor.addBlock("text");
editor.updateDocumentStyles({ fontSize: "16px", color: "#333333" });

if (text) {
  editor.updateBlock(text.id, {
    styles: { color: "#111111" },
  });
}

const current = editor.getTemplate();
```

## Core Concepts

- `TemplateEditor`: main orchestrator
- `Block`: tree-based content structure
- `DocumentStyles`: style defaults for blocks
- Resolved style cascade: `document -> ancestors -> block`
- `DragDropPort`: validation + execution contract used by UI adapters

## Plugin-Based Blocks

Custom blocks are registered via `plugins` in `EditorConfig`.

```ts
import type { BlockPlugin } from "@epistola/headless-editor";

const imagePlugin: BlockPlugin = {
  type: "image",
  create: (id) => ({ id, type: "image", src: "", alt: "", styles: {} }) as any,
  validate: () => ({ valid: true, errors: [] }),
  constraints: {
    canHaveChildren: false,
    allowedChildTypes: [],
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: ["root", "container", "column", "cell"],
  },
  toolbar: {
    visible: true,
    group: "Content",
    order: 20,
    label: "Image",
    icon: "image",
  },
  capabilities: {
    html: true,
    pdf: false,
  },
};

const editor = new TemplateEditor({ plugins: [imagePlugin] });
```

### Toolbar Catalog

UI layers can ask headless for a sorted block catalog:

```ts
const catalog = editor.getBlockCatalog();
// [{ type, label, group, order, visible, addableAtRoot, ... }]
```

Use this to build plugin-driven add buttons.

## Style Cascade Utilities

Exported helpers:

- `resolveDocumentStyles(documentStyles)`
- `resolveBlockStyles(documentStyles, blockStyles)`
- `resolveBlockStylesWithAncestors(documentStyles, ancestorStyles, blockStyles)`

Inheritable keys are exposed via `INHERITABLE_STYLE_KEYS`.

## Frequently Used APIs

- State/template
  - `getTemplate()`
  - `subscribe(...)`
  - `markAsSaved()`
- Block operations
  - `addBlock(type, parentId?)`
  - `updateBlock(id, patch)`
  - `deleteBlock(id)`
  - `moveBlock(id, targetParentId, targetIndex?)`
- Selection
  - `selectBlock(id | null)`
  - `getSelectedBlock()`
- History
  - `undo()` / `redo()`
  - `canUndo()` / `canRedo()`
- Drag-drop
  - `getDragDropPort()`
  - `getDropContainerIds()`

## Expression Helpers

The package exports autocomplete/evaluation helpers used by UI adapters:

- `getExpressionCompletions(...)`
- `buildEvaluationContext(...)`
- `evaluateJsonata(...)`

## Notes

- Built-in block types are auto-registered.
- Additional plugin blocks can be added per editor instance.
- Keep UI-specific rendering logic out of headless.
