# Undo/Redo Implementation Guide

This document outlines the design and implementation of undo/redo functionality for the template editor.

## Overview

Undo/redo allows users to revert and reapply changes to their document structure and styling. This is a critical feature for any editor, as it provides:
- Safety net for experimentation
- Recovery from mistakes
- Standard editor UX (Ctrl+Z/Ctrl+Y)

## Requirements

Users should be able to:
- Press **Ctrl+Z** (or Cmd+Z on Mac) to undo the last change
- Press **Ctrl+Shift+Z** or **Ctrl+Y** (or Cmd+Shift+Z/Cmd+Y on Mac) to redo
- See visual feedback indicating when undo/redo is available
- Have a reasonable history limit (50-100 actions)

## Current State Analysis

### What We Have

The editor uses **Zustand** with **Immer middleware** for state management, which provides:
- Immutable state updates (perfect for snapshots)
- Structural sharing (efficient memory usage)
- Simple API for mutations

**6 state-modifying actions** that need tracking:

| Action | Description | Impact |
|--------|-------------|--------|
| `addBlock` | Adds a block at a specific index | Creates new block in tree |
| `deleteBlock` | Removes a block recursively | Removes block and children |
| `moveBlock` | Moves a block between parents | Complex reordering |
| `updateBlock` | Modifies block properties | Updates styles, content, config |
| `updateDocumentStyles` | Changes global typography/background | Document-level change |
| `updatePageSettings` | Changes page format/margins | Document-level change |

### What Doesn't Need Tracking

- `selectBlock` - UI state only (doesn't affect saved document)
- `setTestData` - Test data separate from template
- `setPreviewOverride` - Preview-only UI state

### Complexity Considerations

**Deep Nesting:**
Blocks can nest in multiple ways:
- Containers: `container → children: Block[]`
- Columns: `columns → columns[].children: Block[]`
- Tables: `table → rows[].cells[].children: Block[]`

**Composite IDs:**
- Columns use `blockId::columnId`
- Tables use `blockId::rowId::cellId`
- This affects how we track parent-child relationships

**Existing Patterns:**
- `findAndUpdateBlock` already handles nested traversal
- Immer makes deep cloning efficient with structural sharing

## Approaches

### Approach A: Command Pattern

**Concept:** Each action creates a command object with `execute()` and `undo()` methods. This is the classic undo/redo pattern.

**How it works:**

```typescript
interface Command {
  execute: () => void;
  undo: () => void;
  description: string;
}

interface UndoRedoState {
  past: Command[];      // Stack of executed commands
  future: Command[];    // Stack of undone commands (for redo)
  maxSize: number;      // History limit
}
```

**Example implementation:**

```typescript
class AddBlockCommand implements Command {
  constructor(
    private block: Block,
    private parentId: string | null,
    private index: number
  ) {}

  execute() {
    // Add block to store
    addBlockToStore(this.block, this.parentId, this.index);
  }

  undo() {
    // Remove the block we just added
    deleteBlockFromStore(this.block.id);
  }

  description = `Add ${this.block.type} block`;
}

// Usage:
const cmd = new AddBlockCommand(textBlock, null, 0);
cmd.execute();           // Do the action
pushCommand(cmd);        // Save to history
// ... later ...
cmd.undo();              // Undo the action
```

#### Pros
✅ **Precise control** - Each action defines its own undo logic
✅ **Efficient memory** - Can store only diffs, not full state
✅ **Clear descriptions** - Each command has a user-friendly label
✅ **Optimizable** - Can batch commands or merge similar ones

#### Cons
❌ **More code** - Every action needs a command class
❌ **Maintenance burden** - Must update commands when actions change
❌ **Complex for nested ops** - moveBlock touches multiple structures
❌ **Error-prone** - Easy to forget to update undo logic

#### When to use
- Large documents where memory is a concern
- Need fine-grained control over undo behavior
- Want to show detailed history ("Added text", "Changed color")
- Have time for thorough testing of each command

---

### Approach B: State Snapshots

**Concept:** Capture the full template state after each action. Restore previous states when undoing.

**How it works:**

```typescript
interface HistoryEntry {
  template: Template;              // Complete template snapshot
  selectedBlockId: string | null;  // Selection state
  timestamp: number;
  action?: string;                 // Optional label
}

interface UndoRedoState {
  past: HistoryEntry[];      // Stack of past states
  future: HistoryEntry[];    // Stack of future states (for redo)
  maxSize: number;           // History limit (e.g., 50)
}
```

**Flow:**

1. **Before action**: Push current state to `past`
2. **Execute action**: Mutate the template
3. **Clear future**: Can't redo after new action
4. **On undo**: Move current → `future`, restore from `past`
5. **On redo**: Move current → `past`, restore from `future`

**Example implementation:**

```typescript
// Before any mutation
pushHistory: (actionLabel?: string) => {
  const { template, selectedBlockId, undoRedo } = get();

  const entry: HistoryEntry = {
    template: structuredClone(template), // Deep clone
    selectedBlockId,
    timestamp: Date.now(),
    action: actionLabel,
  };

  const newPast = [...undoRedo.past, entry];

  // Enforce size limit
  if (newPast.length > undoRedo.maxHistorySize) {
    newPast.shift(); // Remove oldest
  }

  set((state) => {
    state.undoRedo.past = newPast;
    state.undoRedo.future = []; // Clear redo stack
  });
}

// Undo
undo: () => {
  const { undoRedo, template, selectedBlockId } = get();

  if (undoRedo.past.length === 0) return;

  // Save current to future
  const currentEntry = {
    template: structuredClone(template),
    selectedBlockId,
    timestamp: Date.now(),
  };

  // Restore previous state
  const newPast = [...undoRedo.past];
  const previousEntry = newPast.pop()!;

  set((state) => {
    state.template = previousEntry.template;
    state.selectedBlockId = previousEntry.selectedBlockId;
    state.undoRedo.past = newPast;
    state.undoRedo.future = [...undoRedo.future, currentEntry];
  });
}

// Usage in actions:
addBlock: (block, parentId, index) => {
  get().pushHistory(`Add ${block.type} block`);

  set((state) => {
    // ... existing addBlock logic
  });
}
```

#### Pros
✅ **Simple to implement** - Just clone the template
✅ **Works automatically** - Any action works without custom logic
✅ **Less error-prone** - No per-action undo code to maintain
✅ **Immer efficiency** - Structural sharing reduces memory impact
✅ **Selection included** - Selection state preserved automatically

#### Cons
❌ **Higher memory** - Stores entire template each time
❌ **No granular labels** - Can't show detailed action descriptions
❌ **Fixed overhead** - Cloning happens even for small changes

#### When to use
- Documents aren't huge (< 1000 blocks typical)
- Want fast implementation (< 1 hour)
- Prefer simplicity over optimization
- Memory isn't a constraint (5MB for 50 snapshots is fine)

---

### Approach C: Hybrid (Command + Snapshots)

**Concept:** Use commands for simple actions, snapshots for complex ones.

**How it works:**
- Simple actions like `updateBlock` use commands
- Complex actions like `moveBlock` use snapshots
- Best of both worlds

#### Pros
✅ **Flexible** - Optimize where needed
✅ **Efficient** - Save memory on simple operations

#### Cons
❌ **Inconsistent** - Two patterns to maintain
❌ **Complexity** - Harder to reason about
❌ **Overkill** - Probably not worth it for this editor

---

### Approach D: Third-Party Library (zundo)

**Concept:** Use [zundo](https://github.com/charkour/zundo), a Zustand middleware for undo/redo.

**How it works:**

```typescript
import { temporal } from 'zundo';

const useEditorStore = create(
  temporal(
    immer((set, get) => ({
      // ... existing store
    })),
    {
      limit: 50,
      equality: (a, b) => a.template === b.template, // Only track template
    }
  )
);

// Usage:
const { undo, redo, canUndo, canRedo } = useEditorStore.temporal.getState();
```

#### Pros
✅ **Battle-tested** - Used in production apps
✅ **Handles edge cases** - Debouncing, batching, etc.
✅ **Minimal setup** - Just wrap your store
✅ **Well documented** - Good TypeScript support

#### Cons
❌ **Extra dependency** - Another library to maintain
❌ **Less control** - Harder to customize behavior
❌ **Learning curve** - Need to understand the API

---

## Recommended Approach: State Snapshots (Approach B)

For this editor, **State Snapshots** is the best choice because:

1. **Templates are not huge**
   - Typical documents: 10-100 blocks
   - Large documents: < 500 blocks
   - Memory impact is negligible

2. **Immer efficiency**
   - Structural sharing minimizes memory usage
   - Cloning is fast (< 1ms for typical templates)

3. **Simpler maintenance**
   - No per-action undo logic
   - Less code = fewer bugs
   - Easy to understand

4. **Good enough**
   - 50-100 snapshots provides plenty of history
   - Users rarely need more than 10-20 undos

5. **Fast implementation**
   - Can be done in ~1 hour
   - No complex command classes

**Memory calculation:**
- Average template: ~100KB
- 50 snapshots: ~5MB
- With Immer structural sharing: ~2-3MB actual
- Modern browsers: No problem

## Text Changes and Unified Undo

### The Challenge

Text editing in TextBlocks presents a unique challenge for undo/redo. The Tiptap editor fires `onUpdate` on **every keystroke**:

```typescript
// In TextBlock.tsx
onUpdate: ({ editor }) => {
  updateBlock(block.id, { content: editor.getJSON() });
}
```

If we naively wrap `updateBlock` with `pushHistory()`, we get:
- ❌ One snapshot per keystroke
- ❌ History fills up in seconds (50 keystrokes = full history)
- ❌ Undo becomes character-by-character (annoying for users)
- ❌ Users expect to undo words, sentences, or logical chunks

### Approach 1: Separate Undo Stacks (Simpler)

Use **Tiptap's built-in undo** for text, **our undo for structure**.

**How it works:**

```typescript
// Enable Tiptap's history
const editor = useEditor({
  extensions: [
    StarterKit.configure({
      history: true, // ✅ Tiptap handles text undo
    }),
    Underline,
    ExpressionNode,
  ],
  onUpdate: ({ editor }) => {
    // Just update, don't push history
    updateBlock(block.id, { content: editor.getJSON() });
  },
});

// In keyboard handler
if (modifier && e.key === 'z') {
  const isInTextEditor = document.activeElement?.closest('.ProseMirror');

  if (isInTextEditor) {
    // Let Tiptap handle it (automatically)
    return;
  }

  // Otherwise use our structural undo
  useEditorStore.getState().undo();
}
```

**Pros:**
✅ Tiptap handles text grouping intelligently
✅ No extra code needed
✅ Standard ProseMirror behavior
✅ Simple implementation

**Cons:**
❌ Two separate undo stacks
❌ Can't undo across text and structure changes in one operation
❌ Confusing if user adds block, types text, then undoes (expects block gone, but only text undone)

### Approach 2: Unified Stack with Debouncing (Recommended)

Track all changes in a single unified stack, but **debounce text updates** to group keystrokes.

**How it works:**

```typescript
// In editorStore.ts
let textEditTimer: NodeJS.Timeout | null = null;

pushTextHistory: (blockId: string) => {
  // Clear previous timer
  if (textEditTimer) {
    clearTimeout(textEditTimer);
  }

  // Wait 500ms after user stops typing
  textEditTimer = setTimeout(() => {
    const { template, selectedBlockId, undoRedo } = get();

    const entry: HistoryEntry = {
      template: structuredClone(template),
      selectedBlockId,
      timestamp: Date.now(),
      action: 'Edit text',
    };

    const newPast = [...undoRedo.past, entry];
    if (newPast.length > undoRedo.maxHistorySize) {
      newPast.shift();
    }

    set((state) => {
      state.undoRedo.past = newPast;
      state.undoRedo.future = [];
    });

    textEditTimer = null;
  }, 500); // 500ms pause = create history entry
},

// Modified updateBlock to skip history for text
updateBlock: (id: string, updates: Partial<Block>) => {
  // Don't push history for text content updates
  // (handled by pushTextHistory with debouncing)
  if (!('content' in updates)) {
    get().pushHistory('Update block');
  }

  set((state) => {
    // ... existing update logic
  });
}
```

**In TextBlock:**

```typescript
const pushTextHistory = useEditorStore((s) => s.pushTextHistory);

const editor = useEditor({
  extensions: [
    StarterKit.configure({
      history: false, // We handle it
    }),
    Underline,
    ExpressionNode,
  ],
  content: block.content,
  onUpdate: ({ editor }) => {
    const newContent = editor.getJSON();

    // Update immediately (for preview/reactivity)
    updateBlock(block.id, { content: newContent });

    // Push to history after 500ms pause
    pushTextHistory(block.id);
  },
});
```

**Pros:**
✅ Single unified undo stack
✅ Undo works across text and structure
✅ Reasonable granularity (undo paragraphs/sentences)
✅ Simple to understand
✅ No special keyboard handling needed

**Cons:**
❌ 500ms delay feels less responsive than Tiptap's native undo
❌ Not as intelligent as Tiptap's semantic grouping
❌ Multiple text blocks typing simultaneously could interfere

**Debounce timing considerations:**
- **100ms:** Too fast, creates too many snapshots
- **500ms:** Good balance, groups 1-2 sentences
- **1000ms:** Might feel laggy, but groups full paragraphs

### Approach 3: Hybrid with Editor Refs (Most Complex)

Store references to Tiptap editor instances and delegate text undo to them.

**How it works:**

```typescript
// In editorStore.ts
interface EditorState {
  editorRefs: Map<string, Editor>; // blockId -> Editor instance
}

registerEditor: (blockId: string, editor: Editor) => {
  set((state) => {
    state.editorRefs.set(blockId, editor);
  });
}

undo: () => {
  const { undoRedo, editorRefs } = get();

  if (undoRedo.past.length === 0) return;

  const lastEntry = undoRedo.past[undoRedo.past.length - 1];

  // Check if last change was text edit
  if (lastEntry.textEdit) {
    const editor = editorRefs.get(lastEntry.textEdit.blockId);

    if (editor && editor.can().undo()) {
      // Delegate to Tiptap
      editor.commands.undo();

      // Update our stack
      const newPast = [...undoRedo.past];
      newPast.pop();

      set((state) => {
        state.undoRedo.past = newPast;
        state.undoRedo.future = [...undoRedo.future, lastEntry];
      });

      return;
    }
  }

  // Otherwise, normal structure undo
  // ... existing logic
}
```

**Pros:**
✅ Single unified stack
✅ Leverages Tiptap's intelligent grouping
✅ Perfect text editing experience

**Cons:**
❌ Complex: store editor refs, lifecycle management
❌ Editor might be unmounted when undoing
❌ Need to handle editor focus/blur
❌ More points of failure

### Approach 4: Hook into Tiptap's History Extension

Extend Tiptap's History to sync with our store when it creates undo steps.

**How it works:**

```typescript
import { History } from '@tiptap/extension-history';

const CustomHistory = History.extend({
  addOptions() {
    return {
      ...this.parent?.(),
      onUpdate: (blockId: string) => {
        // Called when Tiptap groups changes
        useEditorStore.getState().pushTextHistory(blockId);
      },
    };
  },

  onUpdate() {
    // Hook into Tiptap's grouping logic
    this.options.onUpdate?.(this.editor.id);
  },
});

const editor = useEditor({
  extensions: [
    StarterKit.configure({
      history: false, // Use our custom history
    }),
    CustomHistory.configure({
      onUpdate: (blockId) => {
        // This fires when Tiptap creates a history step
        pushTextHistory(blockId);
      },
    }),
  ],
});
```

**Pros:**
✅ Leverages Tiptap's semantic grouping
✅ Single unified stack
✅ Groups by meaning, not timing

**Cons:**
❌ Requires extending Tiptap extension
❌ Tightly coupled to Tiptap internals
❌ May break with Tiptap updates
❌ More complex to maintain

### Recommendation: Unified with Debouncing (Approach 2)

For this editor, **Approach 2 (Unified with Debouncing)** is the best choice:

**Why:**
1. **Single undo stack** - Users can undo across text and structure changes
2. **Simple implementation** - Just add debouncing logic
3. **Good UX** - 500ms groups 1-2 sentences naturally
4. **No tight coupling** - Doesn't depend on Tiptap internals
5. **Easy to tune** - Adjust debounce timing based on user feedback

**When to reconsider:**
- If users complain about undo granularity (too coarse/fine)
- If you need sub-word undo (unlikely for templates)
- If performance becomes an issue (unlikely)

### Implementation Example

**1. Add debounced text history to store:**

```typescript
// In editorStore.ts

// Debounce timer (outside store)
let textEditTimer: NodeJS.Timeout | null = null;

const store = create(
  immer<EditorState>((set, get) => ({
    // ... existing state

    pushTextHistory: (blockId: string) => {
      if (textEditTimer) {
        clearTimeout(textEditTimer);
      }

      textEditTimer = setTimeout(() => {
        const { template, selectedBlockId, undoRedo } = get();

        // Only push if content actually changed
        const lastEntry = undoRedo.past[undoRedo.past.length - 1];
        if (lastEntry && JSON.stringify(lastEntry.template) === JSON.stringify(template)) {
          return; // No change, skip
        }

        const entry: HistoryEntry = {
          template: structuredClone(template),
          selectedBlockId,
          timestamp: Date.now(),
          action: 'Edit text',
        };

        const newPast = [...undoRedo.past, entry];
        if (newPast.length > undoRedo.maxHistorySize) {
          newPast.shift();
        }

        set((state) => {
          state.undoRedo.past = newPast;
          state.undoRedo.future = [];
        });

        textEditTimer = null;
      }, 500); // Configurable: 300-1000ms
    },

    updateBlock: (id: string, updates: Partial<Block>) => {
      // Skip history for text content (handled by pushTextHistory)
      if (!('content' in updates)) {
        get().pushHistory(`Update ${getBlockTypeLabel(id)}`);
      }

      set((state) => {
        // ... existing update logic
      });
    },
  }))
);
```

**2. Update TextBlock to use debounced history:**

```typescript
export function TextBlockComponent({ block, isSelected = false }: TextBlockProps) {
  const updateBlock = useEditorStore((s) => s.updateBlock);
  const pushTextHistory = useEditorStore((s) => s.pushTextHistory);

  const editor = useEditor({
    extensions: [
      StarterKit.configure({
        history: false, // Disable Tiptap's history
      }),
      Underline,
      ExpressionNode,
    ],
    content: block.content,
    onUpdate: ({ editor }) => {
      const newContent = editor.getJSON();

      // Update immediately
      updateBlock(block.id, { content: newContent });

      // Push to history with debounce
      pushTextHistory(block.id);
    },
  });

  // ... rest of component
}
```

**3. Optional: Make debounce configurable:**

```typescript
interface UndoRedoState {
  past: HistoryEntry[];
  future: HistoryEntry[];
  maxHistorySize: number;
  textEditDebounceMs: number; // NEW: Configurable timing
}

// Initialize
undoRedo: {
  past: [],
  future: [],
  maxHistorySize: 50,
  textEditDebounceMs: 500, // Default: 500ms
}

// Use in pushTextHistory
textEditTimer = setTimeout(() => {
  // ...
}, get().undoRedo.textEditDebounceMs);
```

### Trade-offs Summary

| Approach | Unified Stack | Intelligent Grouping | Complexity | Recommended |
|----------|---------------|---------------------|------------|-------------|
| **Separate Stacks** | ❌ No | ✅ Yes (Tiptap) | Low | No |
| **Debounced** | ✅ Yes | ⚠️ Time-based | Low | **✅ Yes** |
| **Editor Refs** | ✅ Yes | ✅ Yes (Tiptap) | High | No |
| **Hook History** | ✅ Yes | ✅ Yes (Tiptap) | Medium | Maybe |

**Final recommendation:** Start with **Approach 2 (Debounced)**, tune the timing, and only explore more complex approaches if users complain about granularity.

## Implementation Plan

### Phase 1: Add History State to Store

**File:** `src/store/editorStore.ts`

Add to the store state:

```typescript
interface UndoRedoState {
  past: HistoryEntry[];
  future: HistoryEntry[];
  maxHistorySize: number;
}

interface HistoryEntry {
  template: Template;
  selectedBlockId: string | null;
  timestamp: number;
  action?: string;  // Optional label
}

// Add to EditorState
interface EditorState {
  template: Template;
  selectedBlockId: string | null;
  testData: Record<string, unknown>;
  previewOverrides: PreviewOverrides;
  undoRedo: UndoRedoState;  // NEW
}
```

Initialize in store:

```typescript
undoRedo: {
  past: [],
  future: [],
  maxHistorySize: 50,
}
```

### Phase 2: Create History Management Actions

Add these actions to the store:

```typescript
pushHistory: (actionLabel?: string) => {
  const { template, selectedBlockId, undoRedo } = get();

  const entry: HistoryEntry = {
    template: structuredClone(template),
    selectedBlockId,
    timestamp: Date.now(),
    action: actionLabel,
  };

  const newPast = [...undoRedo.past, entry];

  if (newPast.length > undoRedo.maxHistorySize) {
    newPast.shift();
  }

  set((state) => {
    state.undoRedo.past = newPast;
    state.undoRedo.future = [];
  });
},

undo: () => {
  const { undoRedo, template, selectedBlockId } = get();

  if (undoRedo.past.length === 0) return;

  const currentEntry: HistoryEntry = {
    template: structuredClone(template),
    selectedBlockId,
    timestamp: Date.now(),
  };

  const newPast = [...undoRedo.past];
  const previousEntry = newPast.pop()!;

  set((state) => {
    state.template = previousEntry.template;
    state.selectedBlockId = previousEntry.selectedBlockId;
    state.undoRedo.past = newPast;
    state.undoRedo.future = [...undoRedo.future, currentEntry];
  });
},

redo: () => {
  const { undoRedo, template, selectedBlockId } = get();

  if (undoRedo.future.length === 0) return;

  const currentEntry: HistoryEntry = {
    template: structuredClone(template),
    selectedBlockId,
    timestamp: Date.now(),
  };

  const newFuture = [...undoRedo.future];
  const nextEntry = newFuture.pop()!;

  set((state) => {
    state.template = nextEntry.template;
    state.selectedBlockId = nextEntry.selectedBlockId;
    state.undoRedo.past = [...undoRedo.past, currentEntry];
    state.undoRedo.future = newFuture;
  });
},

canUndo: () => get().undoRedo.past.length > 0,
canRedo: () => get().undoRedo.future.length > 0,
```

### Phase 3: Wrap Template-Modifying Actions

For each action that modifies the template, call `pushHistory()` **before** the mutation:

```typescript
addBlock: (block: Block, parentId: string | null = null, index?: number) => {
  get().pushHistory(`Add ${block.type} block`);  // NEW

  set((state) => {
    // ... existing logic
  });
}

deleteBlock: (id: string) => {
  get().pushHistory('Delete block');  // NEW

  set((state) => {
    // ... existing logic
  });
}

moveBlock: (blockId: string, newParentId: string | null, index?: number) => {
  get().pushHistory('Move block');  // NEW

  set((state) => {
    // ... existing logic
  });
}

updateBlock: (id: string, updates: Partial<Block>) => {
  get().pushHistory('Update block');  // NEW

  set((state) => {
    // ... existing logic
  });
}

updateDocumentStyles: (styles: Partial<DocumentStyles>) => {
  get().pushHistory('Update document styles');  // NEW

  set((state) => {
    // ... existing logic
  });
}

updatePageSettings: (settings: Partial<PageSettings>) => {
  get().pushHistory('Update page settings');  // NEW

  set((state) => {
    // ... existing logic
  });
}
```

### Phase 4: Add Keyboard Shortcuts

**File:** `src/components/editor/EditorLayout.tsx` or `src/App.tsx`

Add a keyboard event listener:

```typescript
import { useEffect } from 'react';
import { useEditorStore } from '../store/editorStore';

export function EditorLayout() {
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const isMac = navigator.platform.toUpperCase().indexOf('MAC') >= 0;
      const modifier = isMac ? e.metaKey : e.ctrlKey;

      // Undo: Ctrl+Z (Windows) or Cmd+Z (Mac)
      if (modifier && e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        useEditorStore.getState().undo();
      }

      // Redo: Ctrl+Shift+Z or Ctrl+Y (Windows) or Cmd+Shift+Z (Mac)
      if (
        (modifier && e.shiftKey && e.key === 'z') ||
        (modifier && e.key === 'y')
      ) {
        e.preventDefault();
        useEditorStore.getState().redo();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  // ... rest of component
}
```

### Phase 5: Add UI Indicators (Optional)

Add undo/redo buttons to the editor header:

```tsx
import { useEditorStore } from '../store/editorStore';

export function EditorHeader() {
  const canUndo = useEditorStore((state) => state.canUndo());
  const canRedo = useEditorStore((state) => state.canRedo());
  const undo = useEditorStore((state) => state.undo);
  const redo = useEditorStore((state) => state.redo);

  return (
    <div className="flex items-center gap-2">
      <button
        onClick={undo}
        disabled={!canUndo}
        className="px-3 py-1 text-sm rounded disabled:opacity-50 disabled:cursor-not-allowed hover:bg-gray-100"
        title="Undo (Ctrl+Z)"
      >
        ↶ Undo
      </button>
      <button
        onClick={redo}
        disabled={!canRedo}
        className="px-3 py-1 text-sm rounded disabled:opacity-50 disabled:cursor-not-allowed hover:bg-gray-100"
        title="Redo (Ctrl+Shift+Z)"
      >
        ↷ Redo
      </button>
    </div>
  );
}
```

## Edge Cases & Considerations

### 1. When to Push History

**IMPORTANT:** Push history **before** the mutation, not after.

```typescript
// ✅ CORRECT
addBlock: (block) => {
  get().pushHistory('Add block');  // Save current state
  set((state) => { /* mutate */ });
}

// ❌ WRONG
addBlock: (block) => {
  set((state) => { /* mutate */ });
  get().pushHistory('Add block');  // Too late! New state already set
}
```

**Why?** We need to capture the state that existed *before* the change, so we can restore it on undo.

### 2. Selection State

Include `selectedBlockId` in snapshots:

```typescript
const entry: HistoryEntry = {
  template: structuredClone(template),
  selectedBlockId,  // ✅ Include selection
  timestamp: Date.now(),
};
```

**Why?** Users expect undo to restore both content and selection.

### 3. Compound Operations

Some operations trigger multiple actions. For example, `moveBlock` might internally call `deleteBlock` and `addBlock`.

**Problem:** Each call pushes history, creating extra snapshots.

**Solution A:** Skip history for internal calls:

```typescript
pushHistory: (actionLabel?: string, skipHistory = false) => {
  if (skipHistory) return;
  // ... rest of logic
}

// Internal helper
deleteBlockInternal: (id: string) => {
  // Don't push history
  set((state) => { /* delete */ });
}

// Public API
deleteBlock: (id: string) => {
  get().pushHistory('Delete block');
  get().deleteBlockInternal(id);
}
```

**Solution B (Simpler):** Make operations atomic. Don't call other actions internally.

### 4. Initial State

Don't push history for initial template load:

```typescript
setTemplate: (template: Template) => {
  // NO pushHistory() here - just loading, not user action
  set((state) => {
    state.template = template;
  });
}
```

### 5. Memory Management

With `maxHistorySize: 50`:
- Memory impact: ~2-5MB (with Immer structural sharing)
- Performance: Negligible

If memory becomes an issue:
- Lower to 25-30 snapshots
- Only track last N minutes
- Implement diff-based storage (complex)

### 6. Infinite Loops

**CRITICAL:** `undo()` and `redo()` should **never** call `pushHistory()`.

```typescript
undo: () => {
  // ❌ NO pushHistory() here!
  set((state) => {
    state.template = previousEntry.template;
  });
}
```

**Why?** Would create infinite loop: undo → push history → undo → push history...

### 7. Debouncing (Optional)

For actions that fire rapidly (e.g., typing, dragging), debounce history pushes:

```typescript
let debounceTimer: NodeJS.Timeout;

pushHistoryDebounced: (actionLabel?: string, delay = 500) => {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    get().pushHistory(actionLabel);
  }, delay);
}
```

Use for text editing to avoid creating a snapshot on every keystroke.

## Testing Strategy

### Manual Testing Checklist

- [ ] Add text block → Undo → Text block removed
- [ ] Delete block → Undo → Block restored at correct position
- [ ] Move block → Undo → Block back in original position
- [ ] Update block styles → Undo → Styles reverted
- [ ] Multiple operations → Undo 3 times → Correct states restored
- [ ] Undo all → Redo all → Back to current state
- [ ] Undo → Make new edit → Redo stack cleared (can't redo)
- [ ] Undo/Redo with nested blocks (containers, columns, tables)
- [ ] Undo/Redo restores selection state
- [ ] Keyboard shortcuts work correctly:
  - Ctrl+Z / Cmd+Z for undo
  - Ctrl+Shift+Z / Cmd+Shift+Z for redo
  - Ctrl+Y for redo (Windows)
- [ ] UI buttons disabled when nothing to undo/redo
- [ ] History limited to maxHistorySize (oldest removed)

### Unit Tests (Future)

```typescript
import { describe, it, expect } from 'vitest';
import { useEditorStore } from '../store/editorStore';

describe('Undo/Redo', () => {
  it('should undo addBlock', () => {
    const store = useEditorStore.getState();
    const initialBlocks = store.template.blocks;

    store.addBlock(textBlock, null);
    expect(store.template.blocks.length).toBe(initialBlocks.length + 1);

    store.undo();
    expect(store.template.blocks).toEqual(initialBlocks);
  });

  it('should redo after undo', () => {
    const store = useEditorStore.getState();

    store.addBlock(textBlock, null);
    const afterAdd = store.template.blocks;

    store.undo();
    store.redo();

    expect(store.template.blocks).toEqual(afterAdd);
  });

  it('should clear future on new action', () => {
    const store = useEditorStore.getState();

    store.addBlock(textBlock, null);
    store.undo();
    expect(store.canRedo()).toBe(true);

    store.addBlock(containerBlock, null);
    expect(store.canRedo()).toBe(false);
  });

  it('should respect maxHistorySize', () => {
    const store = useEditorStore.getState();
    store.undoRedo.maxHistorySize = 3;

    for (let i = 0; i < 5; i++) {
      store.addBlock(createTextBlock(), null);
    }

    expect(store.undoRedo.past.length).toBeLessThanOrEqual(3);
  });
});
```

## Performance Considerations

### Memory Usage

**Calculation:**
- Average template: ~100KB
- 50 snapshots: ~5MB
- With Immer structural sharing: ~2-3MB

**Mitigation:**
- Limit to 50 snapshots (configurable)
- Clear old entries automatically
- Don't store non-template state

### CPU Impact

**Cloning cost:**
- `structuredClone()`: ~1ms for typical templates
- Immer structural sharing reduces copying
- Negligible impact on user experience

**When to optimize:**
- If templates exceed 1000 blocks
- If users report lag
- If memory usage becomes an issue

## Future Enhancements

### History Panel

Show a list of past actions:

```tsx
function HistoryPanel() {
  const past = useEditorStore((state) => state.undoRedo.past);

  return (
    <div className="history-panel">
      {past.map((entry, i) => (
        <div key={i} className="history-entry">
          <span>{entry.action}</span>
          <span>{new Date(entry.timestamp).toLocaleTimeString()}</span>
        </div>
      ))}
    </div>
  );
}
```

### Branching History

Allow users to explore different paths (like Git branches).

### Persistent History

Save history to localStorage:

```typescript
// On each change
localStorage.setItem('editor-history', JSON.stringify(undoRedo));

// On load
const savedHistory = localStorage.getItem('editor-history');
if (savedHistory) {
  undoRedo = JSON.parse(savedHistory);
}
```

### Collaborative Undo

In multi-user scenarios:
- Each user has their own undo stack
- Undo only affects their own changes
- Uses operational transforms to resolve conflicts

## Summary

**Recommended Implementation: State Snapshots**

✅ Simple to implement (~1 hour)
✅ Works automatically with all actions
✅ Leverages Immer for efficiency
✅ Sufficient for typical documents
✅ Easy to maintain and debug

**Next Steps:**
1. Add history state to store
2. Implement core undo/redo actions
3. Wrap all template-modifying actions
4. Add keyboard shortcuts
5. Add UI buttons (optional)

**Key Reminders:**
- Push history BEFORE mutations
- Include selection state
- Don't track non-template state
- Avoid infinite loops
- Test thoroughly with nested blocks
