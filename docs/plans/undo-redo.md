# Undo/Redo Redesign: TextChange as a First-Class Undo Entry

## Problem

The current undo/redo integration between ProseMirror (PM) and the EditorEngine uses a side-channel approach: PM text editors register as an `UndoHandler` on focus and unregister on blur. The engine delegates to this handler before consulting its own undo stack. On blur, a "coalesced" undo entry is pushed to the engine stack.

This works but has significant complexity:
- Focus/blur registration dance (`setActiveUndoHandler`)
- `_hasPendingFlush` guard to prevent `_syncFromExternal` from wiping PM history during editing
- `_isSyncing` flag (effectively dead for async Lit renders)
- `beforeinput` event interception to prevent double-undo
- Debounced content sync interleaved with undo logic
- Coalesced undo entry on blur (entire editing session is one engine undo step)

The coalesced entry is also poor UX: after blurring a text block, Cmd+Z reverts the **entire** editing session in one jump rather than character-by-character.

### Why PM's history can't be serialized or externalized

Investigation of PM's internals revealed:
- The `history()` plugin's `spec.state` has no `toJSON`/`fromJSON` — history state cannot be serialized
- `HistoryState`, `Branch`, `Item`, and `historyKey` are all private internals
- `EditorState.create()` always calls `init()` which creates empty history — no way to inject pre-existing history
- Steps CAN be serialized (`Step.toJSON()`), but replaying them requires reimplementing PM's history (remapping, grouping, selection bookmarks, compression)

Therefore, the best approach is to **delegate** to PM's existing history rather than replacing or externalizing it.

## Proposed approach

**Make text editing a first-class entry on the engine's undo stack.**

When the user first types in a text block, push a `TextChange` entry onto the engine's undo stack. This entry holds a reference to the PM view instance and uses PM's `undoDepth()` as the session boundary. The engine calls `pm.undo()`/`pm.redo()` as long as the depth hasn't reached the session boundary, then pops the entry and moves to the next one.

```
Engine undo stack: [ ..., TextChange(blockA), MoveBlock, TextChange(blockA) ]
                         session 1          structural    session 2

Cmd+Z → engine.undo()
  → peek top: TextChange
  → undoDepth(pmState) > undoDepthAtStart?
    → yes: pm.undo(), entry stays on stack
    → no: session exhausted, pop to redo stack, try next entry
```

### Key properties

- **No focus/blur registration** — the TextChange entry works regardless of whether PM is focused
- **Character-level undo after blur** — PM's history persists even when blurred, so Cmd+Z still undoes individual characters
- **Clean session boundaries** — `undoDepth()` from `prosemirror-history` tracks where each session starts, preventing undo from leaking into a previous session
- **Depth-based boundary in both directions** — undo checks `undoDepth > undoDepthAtStart`, redo checks `undoDepth < undoDepthAtEnd`
- **Fallback for destroyed PM** — if the PM view is destroyed (node deleted), the entry falls back to a snapshot-based content restore

## Detailed design

### TextChange entry

```typescript
interface TextChangeEntry {
  type: 'TextChange'
  nodeId: NodeId
  pmView: EditorView | null     // null if PM was destroyed
  contentBefore: unknown         // snapshot for fallback undo
  contentAfter?: unknown         // snapshot for fallback redo (captured lazily)
  undoDepthAtStart: number       // boundary: don't undo past this depth
  undoDepthAtEnd?: number        // target: redo until reaching this depth (set lazily)
}
```

Only two depth values needed — no step counters:
- `undoDepthAtStart`: recorded on creation (before first change in this session)
- `undoDepthAtEnd`: recorded lazily on first undo encounter (= depth after all typing)

### When to push a TextChange

On the **first doc-changing PM transaction** of a new editing session. A new session starts when there's no TextChange for the current PM view already on top of the undo stack.

```typescript
// In dispatchTransaction, when tr.docChanged:
if (!this._isCurrentSessionOnStack()) {
  this.engine.pushTextChange({
    nodeId: this.nodeId,
    pmView: this._pmView,
    contentBefore: this.content,  // current engine content = "before" snapshot
    undoDepthAtStart: undoDepth(this._pmView.state),
  })
}
```

`_isCurrentSessionOnStack()` peeks at the top of the engine's undo stack and checks if it's a TextChange for this PM view.

### Undo flow

```
engine.undo():
  entry = peek top of undo stack
  if no entry: return false

  if entry is TextChange:
    if pmView is alive:
      // Lazily capture undoDepthAtEnd on first undo of this session
      if entry.undoDepthAtEnd is undefined:
        entry.undoDepthAtEnd = undoDepth(pmView.state)

      if undoDepth(pmView.state) > entry.undoDepthAtStart:
        pm.undo()                    // undo one PM event
        sync PM content to engine    // dispatch with skipUndo
        return true                  // entry stays on undo stack
      else:
        // Session exhausted
        entry.contentAfter = currentPmContent  // capture for redo fallback
        pop entry from undo stack → push to redo stack
        return engine.undo()         // fall through to next entry

    if pmView is null/destroyed:
      pop entry from undo stack
      dispatch UpdateNodeProps(nodeId, contentBefore)  // snapshot restore
      push entry to redo stack
      return true

  if entry is Command:
    // existing engine undo logic (unchanged)
```

### Redo flow

```
engine.redo():
  entry = peek top of redo stack
  if no entry: return false

  if entry is TextChange:
    if pmView is alive:
      if undoDepth(pmView.state) < entry.undoDepthAtEnd:
        pm.redo()                    // redo one PM event
        sync PM content to engine    // dispatch with skipUndo
        return true                  // entry stays on redo stack
      else:
        // Session fully redone
        pop entry from redo stack → push to undo stack
        return engine.redo()         // fall through to next entry

    if pmView is null/destroyed:
      pop entry from redo stack
      dispatch UpdateNodeProps(nodeId, contentAfter)   // snapshot restore
      push entry to undo stack
      return true

  if entry is Command:
    // existing engine redo logic (unchanged)
```

### Content sync (simplified)

The debounced content sync to the engine is still needed — other UI components (inspector, tree, canvas) read content from the engine doc, not PM. But it becomes **purely a sync mechanism** with no undo logic:

```typescript
// On PM doc change (debounced, 300ms):
engine.dispatch(UpdateNodeProps { content: pmDoc.toJSON() }, { skipUndo: true })
```

No `_hasPendingFlush`, no `_isSyncing` flag, no coalesced undo entries, no `_contentBeforeEditing` snapshots. The debounce just keeps the engine in sync for rendering.

Additionally, after each `pm.undo()` or `pm.redo()` call from the engine, the engine syncs the PM content immediately (no debounce needed since the engine initiated the change).

### `_syncFromExternal` (simplified)

External content changes (engine undo restoring content via snapshot fallback, inspector edits) still need to update PM. But the guard logic is much simpler:

```typescript
// In Lit updated():
if (changed.has('content') && this._pmView) {
  // Only sync if the content actually differs from PM's current state
  const pmJson = JSON.stringify(this._pmView.state.doc.toJSON())
  const engineJson = JSON.stringify(this.content)
  if (pmJson !== engineJson) {
    // Replace PM state (this is a genuine external change)
    const doc = this._parseContent(this.content)
    const newState = EditorState.create({ doc, plugins: this._pmView.state.plugins })
    this._pmView.updateState(newState)
  }
}
```

No `_isSyncing` flag needed. No `_hasPendingFlush` guard. The JSON comparison against PM's **current** state (not a cached `_lastContentJson`) is the only guard.

## Edge cases

### 1. Type in A, move A, type in A again

```
Stack: [TextChange(A, session1), MoveBlock, TextChange(A, session2)]
```

If the move destroys and recreates the PM view, `session1`'s pmView reference is stale. When undo reaches it, the fallback snapshot restore kicks in.

If the move preserves the PM view (same Lit element), `undoDepthAtStart` correctly delineates sessions:
- session2: `undoDepthAtStart = N` (depth after session1)
- session1: `undoDepthAtStart = 0`

Undo undoes session2 until depth reaches N, then MoveBlock, then session1 until depth reaches 0.

### 2. Type, PM-undo partially, type more

User types "hello", undoes "lo" (PM undo), types "la":
- PM state: "hella"
- PM's redo stack is cleared by the new typing (standard PM behavior)
- The TextChange entry is still on top of the engine stack (same session)
- Undo would go: "hell" → "hel" → "he" → "h" → "" (PM handles all of it)

### 3. Type in A, type in B, undo all

```
Stack: [TextChange(A, undoDepthAtStart=0), TextChange(B, undoDepthAtStart=3)]
```

Undo: session B (depth 6→5→4→3), then session A (depth 3→2→1→0). Clean boundaries.
Redo: session A (undoDepthAtEnd=3, depth 0→1→2→3), then session B (undoDepthAtEnd=6, depth 3→4→5→6).

### 4. Node deleted while TextChange is on stack

User types in block A, then deletes block A:
```
Stack: [TextChange(A, pmView), RemoveNode(A)]
```

Undo RemoveNode → block A is restored (new PM view created). Then undo reaches TextChange(A) — but `pmView` points to the old destroyed view.

Fallback: apply `contentBefore` via `UpdateNodeProps`. The text reverts to the pre-editing state.

### 5. PM view destroyed by re-render

If a Lit re-render destroys and recreates the `<epistola-text-editor>` element, the PM view is destroyed. The TextChange entry detects this via `pmView.dom.isConnected === false` or by the text editor setting `entry.pmView = null` in `disconnectedCallback`.

### 6. Pushing TextChange clears redo stack

Standard undo semantics: any new action invalidates redo history. Pushing a TextChange on first keystroke is a new action, so the redo stack is cleared. This is correct.

### 7. Same block, blur and refocus without other actions

User types in A, blurs, clicks back on A, types more. No other actions in between.

The TextChange(A) is still on top of the undo stack. The check `_isCurrentSessionOnStack()` sees it's still on top → no new TextChange pushed. The session continues. PM's undo history covers all typing across the blur/refocus.

This is better UX than the current approach, where blur pushes a coalesced entry.

## UndoStack changes

The `UndoStack` currently only holds `Command` objects. It needs to become polymorphic:

```typescript
type UndoEntry = Command | TextChangeEntry

class UndoStack {
  private _undoStack: UndoEntry[] = []
  private _redoStack: UndoEntry[] = []

  peek(): UndoEntry | undefined    // new: peek without popping
  peekRedo(): UndoEntry | undefined // new: peek redo stack

  // push, undo (pop), redo (pop), pushRedo, pushUndo — same signatures but UndoEntry
}
```

## What gets removed

| Current code | Why |
|---|---|
| `UndoHandler` interface | TextChange entries replace the strategy pattern |
| `setActiveUndoHandler()` | No registration needed |
| Focus handler undo registration | TextChange works regardless of focus |
| Blur handler undo unregistration | Same |
| `_hasPendingFlush` guard | No `_syncFromExternal` history-wipe risk |
| `_isSyncing` flag | Not needed with simplified sync |
| `_contentBeforeEditing` | Captured in TextChange entry instead |
| `_flushContent()` undo logic | No coalesced entries |
| `beforeinput` handler | Only needed to prevent double-undo from strategy pattern |
| `pushUndoEntry()` on engine | Replaced by `pushTextChange()` |

### What gets added

| New code | Where |
|---|---|
| `TextChangeEntry` type | `engine/undo.ts` |
| `UndoEntry` union type | `engine/undo.ts` |
| `peek()` / `peekRedo()` on UndoStack | `engine/undo.ts` |
| `isTextChange()` type guard | `engine/undo.ts` |
| `_undoTextChange()` / `_redoTextChange()` | `engine/EditorEngine.ts` |
| `pushTextChange()` | `engine/EditorEngine.ts` |
| `_isCurrentSessionOnStack()` | `ui/EpistolaTextEditor.ts` |
| PM lifecycle cleanup (nullify pmView on disconnect) | `ui/EpistolaTextEditor.ts` |

## Files changed

| File | Change |
|---|---|
| `engine/undo.ts` | Add `TextChangeEntry`, `UndoEntry` union type, `peek()` methods |
| `engine/EditorEngine.ts` | Remove `UndoHandler`, add TextChange handling in `undo()`/`redo()`, add `pushTextChange()` |
| `engine/index.ts` | Update exports |
| `ui/EpistolaTextEditor.ts` | Remove handler registration, simplify to push TextChange on first change, simplify content sync |
| `ui/EpistolaEditor.ts` | No change (keydown handler stays the same) |
| `prosemirror/plugins.ts` | No change (history plugin stays) |
| `engine/engine.test.ts` | Replace UndoHandler strategy tests with TextChange tests |

## Verification

1. `pnpm --filter @epistola/editor-v2 test` — all tests pass
2. `pnpm --filter @epistola/editor-v2 build` — clean build
3. Manual: Type in text block → Cmd+Z undoes characters (PM handles via TextChange)
4. Manual: Type, blur, Cmd+Z → still undoes characters (PM persists after blur)
5. Manual: Type in A, move A, type in A → undo reverses session 2, then move, then session 1
6. Manual: Type, delete block, undo delete, undo → snapshot fallback restores pre-edit content
7. Manual: Full undo then full redo → returns to original state
8. Manual: Type in A, type in B, undo all, redo all → sessions correctly delineated
