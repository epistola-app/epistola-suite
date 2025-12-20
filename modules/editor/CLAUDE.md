# Claude Instructions for Template Editor

This document provides context and guidelines for working on this codebase.

## Project Overview

This is a **visual template editor** for creating dynamic document templates (invoices, letters, reports, etc.). Users design templates with drag-and-drop blocks, expressions, conditionals, and loops that render to HTML/PDF with data.

**Key concept**: Templates are data structures containing blocks. Each block can have dynamic content via expressions (`{{customer.name}}`). The editor shows a live preview of how the template renders with test data.

## Tech Stack

| Technology | Purpose |
|------------|---------|
| React 19 | UI framework |
| TypeScript | Type safety |
| Vite | Build tool |
| Zustand + Immer | State management |
| TipTap 3 | Rich text editing |
| dnd-kit | Drag and drop |
| Tailwind CSS 4 | Styling |

## Project Structure

```
src/
├── types/
│   ├── template.ts      # Core data models (Template, Block types)
│   └── styles.ts        # Style constants and helpers
├── store/
│   └── editorStore.ts   # Zustand store (single source of truth)
├── context/
│   ├── EvaluatorContext.tsx  # Expression evaluation provider
│   └── ScopeContext.tsx      # Loop variable scope tracking
├── services/
│   └── expression/      # Expression evaluators (Direct, Iframe sandboxed)
├── components/
│   ├── editor/          # Main editor components
│   │   ├── EditorLayout.tsx  # Main layout
│   │   ├── Canvas.tsx        # Editable area
│   │   ├── BlockPalette.tsx  # Draggable block types
│   │   └── Preview.tsx       # Live HTML preview
│   ├── blocks/          # Block type components
│   │   ├── BlockRenderer.tsx      # Block dispatcher + selection
│   │   ├── TextBlock.tsx          # Rich text with TipTap
│   │   ├── ContainerBlock.tsx     # Nested block container
│   │   ├── ConditionalBlock.tsx   # If/else logic
│   │   ├── LoopBlock.tsx          # Array iteration
│   │   ├── ExpressionNode.tsx     # TipTap extension for {{expr}}
│   │   └── ExpressionEditor.tsx   # Expression input with autocomplete
│   └── styling/         # Style inspector components
│       ├── StyleSidebar.tsx       # Main inspector panel
│       ├── DocumentStyleEditor.tsx
│       ├── BlockStyleEditor.tsx
│       └── inputs/                # Reusable input components
```

## Key Patterns

### State Management

All state lives in Zustand store (`editorStore.ts`). Use selectors for performance:

```typescript
// Good - only re-renders when selectedBlockId changes
const selectedBlockId = useEditorStore((s) => s.selectedBlockId);

// Avoid - re-renders on any store change
const store = useEditorStore();
```

### Block Structure

Blocks follow a union type pattern:

```typescript
type Block = TextBlock | ContainerBlock | ConditionalBlock | LoopBlock;

interface BaseBlock {
  id: string;
  type: string;
  styles?: CSSProperties;
}
```

Blocks with children (`container`, `conditional`, `loop`) have a `children: Block[]` array.

### Expression Evaluation

Expressions are evaluated asynchronously via the `useEvaluator` hook:

```typescript
const { evaluate, isReady } = useEvaluator();
const result = await evaluate('customer.name', context);
if (result.success) {
  // use result.value
}
```

Two evaluators exist:
- **DirectEvaluator**: Fast but uses `Function()` (not sandboxed)
- **IframeEvaluator**: Sandboxed in iframe, slightly slower

### Recursive Block Operations

Finding/updating nested blocks uses recursive helpers:

```typescript
// In editorStore.ts
function findAndUpdateBlock(blocks, id, updater) {
  // Recursively finds and updates/removes block by ID
}
```

### Style Inheritance

Document styles cascade to blocks for typography properties only:
- Inheritable: `fontFamily`, `fontSize`, `fontWeight`, `color`, `lineHeight`, `letterSpacing`, `textAlign`
- Not inherited: spacing, borders, background, layout

See `mergeStyles()` in `types/styles.ts`.

## Commands

```bash
npm install      # Install dependencies
npm run dev      # Start dev server (http://localhost:5173)
npm run build    # Production build (outputs to dist/)
npm run preview  # Preview production build
npm run lint     # Run ESLint
```

## Conventions

### File Naming
- Components: PascalCase (`BlockRenderer.tsx`)
- Utilities/types: camelCase (`editorStore.ts`, `template.ts`)
- One component per file

### Component Structure
```typescript
interface ComponentProps {
  // props interface above component
}

export function Component({ prop }: ComponentProps) {
  // hooks first
  // handlers next
  // render last
}
```

### Styling
- Use Tailwind utility classes
- Component-specific styles inline
- Block styles stored as React `CSSProperties` (camelCase)
- Convert to CSS string via `styleToString()` for HTML output

### State Updates
- Use Immer's draft state in Zustand actions
- Never mutate state directly
- Keep actions in store, not components

## Important Considerations

### TipTap Integration
- TextBlock uses TipTap for rich text
- Custom `ExpressionNode` extension for `{{expr}}` chips
- Content stored as TipTap JSON (`JSONContent` type)
- Converted to HTML in Preview via `tiptapToHtml()`

### Drag & Drop
- Uses dnd-kit with `pointerWithin` collision detection
- Each block is both draggable and droppable
- Drop zones appear between blocks
- Block palette items create new blocks on drop

### Preview Rendering
- Async rendering with expression evaluation
- Debounced (100ms) to avoid excessive updates
- Renders to HTML string via `dangerouslySetInnerHTML`
- A4 paper simulation at 50% scale

### Expression Autocomplete
- `ExpressionEditor` provides autocomplete from test data paths
- Scope context tracks loop variables for nested expressions
- Shows type info (array, string, number, object)

## Common Tasks

### Adding a New Block Type

1. Add type to `types/template.ts`:
   ```typescript
   export interface NewBlock extends BaseBlock {
     type: 'new';
     // block-specific fields
   }
   ```
2. Add to `Block` union type
3. Create `NewBlockComponent` in `components/blocks/`
4. Add case to `BlockRenderer.tsx` switch
5. Add to `BlockPalette.tsx`
6. Add rendering logic to `Preview.tsx`

### Adding a New Style Property

1. Add input to `BlockStyleEditor.tsx` (and `DocumentStyleEditor.tsx` if inheritable)
2. If new input type needed, create in `components/styling/inputs/`
3. Style will automatically apply via existing `block.styles` mechanism

### Adding a Store Action

1. Add to `EditorActions` interface in `editorStore.ts`
2. Implement in store with Immer draft state
3. Use in components via `useEditorStore((s) => s.actionName)`

## Documentation

- `docs/todo.md` - Roadmap and progress tracking
- `docs/styling.md` - Styling system documentation
- `docs/architecture.md` - Architecture overview

## Testing

Currently no tests. When adding:
- Unit tests for expression evaluation
- Component tests for blocks
- Integration tests for editor flows

## Known Limitations

- No undo/redo yet
- No persistence (save/load)
- Expression evaluation not fully sandboxed in "Direct" mode
- Large templates may have performance issues
- No table or image blocks yet

## Getting Help

- Check existing code for patterns
- Read the docs in `docs/` folder
- Expression system is in `services/expression/`
- Style system is in `components/styling/` and `types/styles.ts`
