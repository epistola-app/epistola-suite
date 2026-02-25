# Phase 4: Rich Text — ProseMirror Integration

## Context

The editor-v2 canvas renders text nodes as static placeholders (`"Text content"` — `EpistolaCanvas.ts:322`). Phase 4 integrates ProseMirror directly (not TipTap) for rich text editing, expression chips (`{{customer.name}}`), and a floating bubble menu.

**Why ProseMirror direct instead of TipTap**: TipTap is a convenience wrapper. Going direct gives us full control, smaller bundle, no abstraction leaks. Our needs are simple (basic formatting + one custom node type), so the extra boilerplate is minimal.

The **data model schema** must also be passed into the editor so the expression popover can show available field paths.

## Key design decisions

- **ProseMirror direct**: Use `prosemirror-*` packages directly. No TipTap.
- **Expression editor**: Simple `<input>` in a `<dialog>`. Shows available field paths from data model schema.
- **Toolbar**: Floating bubble menu positioned with `@floating-ui/dom` (lightweight, 3KB). Appears on text selection.
- **Undo**: ProseMirror's `prosemirror-history` while focused. Engine undo on blur (node-level).
- **Content sync**: `isSyncing` flag + JSON equality check prevents loops.
- **JSON format**: Same TipTap-compatible JSON format (ProseMirror's `node.toJSON()` / `Node.fromJSON()`). Backend `TipTapConverter.kt` unchanged.

## ProseMirror packages needed

```
prosemirror-model        # Schema, Node, Fragment
prosemirror-state        # EditorState, Transaction, Plugin
prosemirror-view         # EditorView, NodeView, Decoration
prosemirror-transform    # Transform steps (implicit dep)
prosemirror-commands     # toggleMark, setBlockType, wrapInList, etc.
prosemirror-keymap       # keymap plugin
prosemirror-inputrules   # inputRule for {{ trigger
prosemirror-schema-basic # paragraph, heading, marks (bold/italic/code)
prosemirror-schema-list  # bulletList, orderedList, listItem
prosemirror-history      # undo/redo
prosemirror-dropcursor   # cursor during drag
prosemirror-gapcursor    # cursor in structural gaps
@floating-ui/dom         # bubble menu positioning
```

## Implementation steps

### Step 1: Dependencies

**File**: `modules/editor-v2/package.json`

Add all `prosemirror-*` packages listed above + `@floating-ui/dom`.

Run `pnpm install`.

### Step 2: ProseMirror schema definition

**New file**: `modules/editor-v2/src/main/typescript/prosemirror/schema.ts`

Define the document schema combining `prosemirror-schema-basic` + `prosemirror-schema-list` + custom:
- Nodes: `doc`, `paragraph`, `heading` (levels 1-3), `bulletList`, `orderedList`, `listItem`, `text`, `expression` (inline atom)
- Marks: `bold` (`strong`), `italic` (`em`), `underline`, `strikethrough`
- `expression` node: inline, atom, attrs `{ expression: string, isNew: boolean }`

### Step 3: Expression node view

**New file**: `modules/editor-v2/src/main/typescript/prosemirror/ExpressionNodeView.ts`

ProseMirror `NodeView` implementation:
- `dom`: `<span class="expression-chip">` showing expression text
- Click → open `<dialog>` with `<input>` + field path list
- Field paths come from `schema-paths.ts` (Step 5)
- Enter → update node attrs via transaction
- Escape → cancel (delete if `isNew`, else close)
- Auto-opens if `isNew`
- `update(node)`: refresh display text
- `destroy()`: clean up dialog

### Step 4: Plugins & keymaps

**New file**: `modules/editor-v2/src/main/typescript/prosemirror/plugins.ts`

Create plugins array:
- `history()` — undo/redo
- `keymap(baseKeymap)` — basic editing keys
- `keymap(markKeymap)` — Ctrl+B/I/U shortcuts using `toggleMark`
- `inputRules(rules)` — includes `{{` rule for expression insertion
- `dropCursor()`, `gapCursor()`
- Custom selection-change plugin for bubble menu show/hide

**New file**: `modules/editor-v2/src/main/typescript/prosemirror/input-rules.ts`

- `{{` input rule: on match, replace with expression node `{ expression: '', isNew: true }`
- Heading shortcut rules (optional): `# ` → h1, `## ` → h2, `### ` → h3

### Step 5: Schema field path extractor + dataModel API

**New file**: `modules/editor-v2/src/main/typescript/engine/schema-paths.ts`

```typescript
extractFieldPaths(schema: object): { path: string; type: string }[]
```

Walks JSON Schema `properties` recursively (depth limit ~5). Returns dot-notation paths.

**File**: `modules/editor-v2/src/main/typescript/lib.ts`
- Add `dataModel?: object` and `dataExamples?: object[]` to `EditorOptions`

**File**: `modules/editor-v2/src/main/typescript/engine/EditorEngine.ts`
- Store and expose `dataModel` / `dataExamples` as read-only

### Step 6: Bubble menu

**New file**: `modules/editor-v2/src/main/typescript/prosemirror/bubble-menu.ts`

Uses `@floating-ui/dom` for positioning:
- Creates `<div class="pm-bubble-menu">` with formatting buttons
- Buttons: **B** | **I** | **U** | ~~S~~ | H1 H2 H3 | UL OL | `{{}}` insert expression
- Shows when text is selected (non-empty selection), hides otherwise
- Active state from `markActive(state, markType)` / `blockActive(state, nodeType)`
- Position updates via `computePosition()` anchored to selection range
- Exposed as a ProseMirror plugin that hooks into `update(view, prevState)`

### Step 7: Text editor Lit component

**New file**: `modules/editor-v2/src/main/typescript/ui/EpistolaTextEditor.ts`

Lit component `<epistola-text-editor>` (Light DOM):
- Properties: `nodeId`, `content` (JSON | null), `resolvedStyles`, `engine`, `isSelected`
- `firstUpdated()`:
  - Create ProseMirror `Schema`, `EditorState`, `EditorView`
  - Pass plugins from Step 4
  - Set up `dispatchTransaction` to handle content sync
- `disconnectedCallback()`: destroy `EditorView`
- `updated(changed)`: sync content on external changes, blur on deselect

**Content sync** (same pattern, ProseMirror API):
1. `dispatchTransaction(tr)` → apply to state → if `tr.docChanged` and not `isSyncing` → dispatch `UpdateNodeProps({ content: state.doc.toJSON() })`
2. External content change → compare JSON → if different, create new `EditorState` with `EditorState.create()` and `view.updateState()`

**Resolved styles**: applied via `styleMap` on the `.prosemirror-container` div.

### Step 8: Update canvas

**File**: `modules/editor-v2/src/main/typescript/ui/EpistolaCanvas.ts`

Replace text placeholder in `_renderLeafNode()`:
```typescript
case 'text':
  return html`
    <epistola-text-editor
      .nodeId=${nodeId}
      .content=${node.props?.content ?? null}
      .resolvedStyles=${this._getResolvedStyles(nodeId)}
      .engine=${this.engine}
      .isSelected=${this.selectedNodeId === nodeId}
    ></epistola-text-editor>
  `
```

### Step 9: CSS

**New file**: `modules/editor-v2/src/main/typescript/styles/prosemirror.css`

- `.prosemirror-container` — min-height, padding
- `.ProseMirror` — outline:none, typography for p/h1/h2/h3/ul/ol
- `.expression-chip` — inline pill, blue bg, mono font, clickable
- `.pm-bubble-menu` — flex toolbar, white bg, shadow, button states
- `.expression-dialog` — dialog, input, field list

**File**: `modules/editor-v2/src/main/typescript/editor.css` — add `@import './styles/prosemirror.css'`

### Step 10: Tests

**New file**: `modules/editor-v2/src/test/prosemirror/schema.test.ts`
- Schema creates valid doc
- Expression node serializes/deserializes correctly (toJSON/fromJSON roundtrip)
- Marks apply correctly

**New file**: `modules/editor-v2/src/test/prosemirror/input-rules.test.ts`
- `{{` creates expression node with `isNew: true`

**New file**: `modules/editor-v2/src/test/engine/schema-paths.test.ts`
- Flat/nested objects → correct dot-notation paths
- Arrays, depth limit, empty schema

### Step 11: CHANGELOG

## Files summary

| File | Action |
|------|--------|
| `modules/editor-v2/package.json` | Add prosemirror-* deps + @floating-ui/dom |
| `modules/editor-v2/src/main/typescript/lib.ts` | Add dataModel/dataExamples to EditorOptions |
| `modules/editor-v2/src/main/typescript/engine/EditorEngine.ts` | Store/expose dataModel |
| `modules/editor-v2/src/main/typescript/engine/schema-paths.ts` | **New** — JSON Schema → field paths |
| `modules/editor-v2/src/main/typescript/prosemirror/schema.ts` | **New** — ProseMirror schema definition |
| `modules/editor-v2/src/main/typescript/prosemirror/ExpressionNodeView.ts` | **New** — expression chip NodeView + dialog |
| `modules/editor-v2/src/main/typescript/prosemirror/plugins.ts` | **New** — keymap, history, input rules, etc. |
| `modules/editor-v2/src/main/typescript/prosemirror/input-rules.ts` | **New** — `{{` rule + heading shortcuts |
| `modules/editor-v2/src/main/typescript/prosemirror/bubble-menu.ts` | **New** — floating toolbar plugin |
| `modules/editor-v2/src/main/typescript/ui/EpistolaTextEditor.ts` | **New** — Lit ProseMirror wrapper |
| `modules/editor-v2/src/main/typescript/ui/EpistolaCanvas.ts` | Render `<epistola-text-editor>` |
| `modules/editor-v2/src/main/typescript/styles/prosemirror.css` | **New** — all PM/expression/bubble styles |
| `modules/editor-v2/src/main/typescript/editor.css` | Import prosemirror.css |
| Tests (3 new files) | Schema, input rules, schema-paths |
| `CHANGELOG.md` | Update |

## Reference files (V1 — concepts to port)

| File | What to reuse |
|------|--------------|
| `modules/editor/...ExpressionNode.tsx:144-217` | Node definition pattern (attrs, parseHTML, renderHTML, input rule, command) |
| `modules/editor/...ExpressionNode.tsx:24-141` | NodeView behavior (chip display, popover open/save/cancel logic) |
| `modules/editor/...TextBlock.tsx:66-149` | BubbleMenu button set and formatting commands |

## JSON format compatibility

ProseMirror's `node.toJSON()` produces the same structure as TipTap's `editor.getJSON()` — both are ProseMirror JSON. The backend `TipTapConverter.kt` (which reads `type`, `content`, `marks`, `attrs`) works unchanged.

## Verification

1. `pnpm install` — deps install
2. `pnpm --filter @epistola/editor-v2 build` — builds
3. `pnpm --filter @epistola/editor-v2 test` — all tests pass
4. Manual: click text block → ProseMirror activates, type text
5. Manual: select text → bubble menu, formatting works
6. Manual: type `{{` → expression chip, dialog with field paths
7. Manual: blur → content persisted in engine
8. Manual: engine undo → content restored