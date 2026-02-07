# Template Editor v2

A framework-agnostic document template editor with headless and UI modes.

## Quick Start

### Headless Mode (No UI)

Use the headless API when you need editor functionality without DOM:

```typescript
import { createEditor, registerAllBlocks } from '@epistola/editor-v2';

// Register block types (required before creating editor)
registerAllBlocks();

// Create headless editor
const editor = createEditor({
  template: myTemplate,
  onSave: async (template) => {
    await saveToServer(template);
  },
});

// Mutate state
const block = editor.addBlock("text", null, 0);
editor.updateBlock(block.id, { styles: { color: "red" } });

// Undo/redo
editor.undo();
editor.redo();

// Subscribe to changes (for preview updates)
const unsubscribe = editor.onChange((template, changeType) => {
  console.log(`Changed: ${changeType}`);
  renderPreview(template);
});

// Cleanup
unsubscribe();
editor.dispose();
```

### With Full UI

Mount the complete editor with palette, canvas, and sidebar:

```typescript
import { mountEditor, registerAllBlocks } from '@epistola/editor-v2';

// Register block types
registerAllBlocks();

// Mount editor into container
const instance = mountEditor({
  container: document.getElementById('editor-root'),
  template: myTemplate,
  onSave: async (template) => {
    await saveToServer(template);
  },
});

// Access state
const currentTemplate = instance.getTemplate();

// History
instance.undo();
instance.redo();

// Save
await instance.saveNow();

// Cleanup
instance.unmount();
```

## API Reference

### Headless Editor (`createEditor`)

```typescript
interface Editor {
  // State Access
  getTemplate(): Template;
  getSelectedBlockId(): string | null;
  setSelectedBlockId(blockId: string | null): void;

  // Block Mutations
  addBlock(type: BlockType, parentId: string | null, index: number): Block | null;
  updateBlock(blockId: string, updates: Partial<Block>): void;
  deleteBlock(blockId: string): void;
  moveBlock(blockId: string, newParentId: string | null, newIndex: number): void;

  // Document Mutations
  updateDocumentStyles(styles: Partial<DocumentStyles>): void;
  updatePageSettings(settings: Partial<PageSettings>): void;
  updateTheme(themeId: string | null): void;

  // History
  undo(): boolean;
  redo(): boolean;
  canUndo(): boolean;
  canRedo(): boolean;
  clearHistory(): void;

  // Persistence
  isDirty(): boolean;
  getSaveStatus(): SaveStatus;
  saveNow(): Promise<void>;
  markSaved(): void;

  // Template Replacement
  setTemplate(template: Template): void;

  // Subscriptions
  onChange(listener: ChangeListener): () => void;
  onSaveStatusChange(listener: SaveStatusListener): () => void;

  // Lifecycle
  dispose(): void;
}
```

### Change Types

The `onChange` listener receives a change type indicating what changed:

| Type | Description |
|------|-------------|
| `block-added` | New block was added |
| `block-updated` | Existing block was modified |
| `block-deleted` | Block was removed |
| `block-moved` | Block was moved to new location |
| `document-styles` | Document styles changed |
| `page-settings` | Page settings changed |
| `theme` | Theme ID changed |
| `selection` | Selected block changed |
| `template-replaced` | Entire template was replaced |
| `undo` | Undo operation performed |
| `redo` | Redo operation performed |

### Block Types

The editor supports these block types:

| Type | Category | Description |
|------|----------|-------------|
| `text` | content | Rich text block with formatting |
| `image` | content | Image with source and alt text |
| `container` | structure | Generic block container |
| `columns` | structure | Multi-column layout (2-4 columns) |
| `table` | structure | Table with rows and cells |
| `conditional` | logic | Content shown based on expression |
| `loop` | logic | Repeated content from data array |
| `expression` | data | Inline data expression |
| `spacer` | layout | Vertical spacing |
| `divider` | layout | Horizontal line separator |

### Custom Block Types

Register custom block types using the registry:

```typescript
import { registerBlock, type BlockDefinition } from '@epistola/editor-v2';

const myBlockDef: BlockDefinition<MyBlock> = {
  type: "my-block",
  label: "My Block",
  category: "content",
  icon: "<svg>...</svg>",
  createDefault: () => ({
    id: crypto.randomUUID(),
    type: "my-block",
    customProp: "default",
  }),
  // For container blocks, add:
  getChildren: (block) => block.children,
  setChildren: (block, children) => ({ ...block, children }),
};

registerBlock(myBlockDef);
```

## Architecture

See [ARCHITECTURE.md](./ARCHITECTURE.md) for module structure and design decisions.
