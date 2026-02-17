---
name: editor-component
description: Create a new Lit web component for the editor module. Use when building a new UI component, panel, or interactive element for the template editor.
---

Create a new Lit web component in the editor module.

**Input**: The component name and its purpose/behavior.

## Decision Points

Ask the user (if not already specified):
- What is the component name?
- What does it render/do?
- Does it need access to `EditorEngine`?
- Which **component pattern** applies? (subscription-based vs prop-reactive)

## Component Patterns

There are two distinct patterns based on how the component interacts with the engine:

### 1. Subscription-Based (listens to engine events)

**Reference**: `EpistolaPreview.ts` — `modules/editor/src/main/typescript/ui/EpistolaPreview.ts`

The component subscribes to engine events and updates internal `@state()` when events fire.

```typescript
@customElement('epistola-my-component')
export class EpistolaMyComponent extends LitElement {
    override createRenderRoot() { return this }

    @property({ attribute: false }) engine?: EditorEngine
    @state() private _someState: SomeType = initial

    private _unsub?: () => void

    override connectedCallback(): void {
        super.connectedCallback()
        this._setup()
    }

    override updated(changed: Map<string, unknown>): void {
        if (changed.has('engine')) {
            this._teardown()
            this._setup()
        }
    }

    override disconnectedCallback(): void {
        this._teardown()
        super.disconnectedCallback()
    }

    private _setup(): void {
        if (!this.engine) return
        this._unsub = this.engine.events.on('doc:change', ({ doc }) => {
            this._someState = /* derive from doc */
        })
    }

    private _teardown(): void {
        this._unsub?.()
        this._unsub = undefined
    }

    override render() {
        if (!this.engine) return nothing
        return html`<div class="my-component">...</div>`
    }
}
```

**When**: The component needs to react to document changes, selection changes, or example changes over time (previews, status indicators, tree views).

### 2. Prop-Reactive (receives data via properties)

**Reference**: `EpistolaInspector.ts` — `modules/editor/src/main/typescript/ui/EpistolaInspector.ts`

The component receives data through `@property()` and renders based on current prop values. It dispatches commands back to the engine.

```typescript
@customElement('epistola-my-inspector')
export class EpistolaMyInspector extends LitElement {
    override createRenderRoot() { return this }

    @property({ attribute: false }) engine?: EditorEngine
    @property({ attribute: false }) doc?: TemplateDocument
    @property({ attribute: false }) selectedNodeId?: string | null

    override render() {
        if (!this.engine || !this.doc) return nothing
        // Render based on current props
        return html`...`
    }

    private _handleChange(value: string) {
        this.engine?.execute({
            type: 'UpdateNodeProps',
            nodeId: this.selectedNodeId!,
            props: { someField: value },
        })
    }
}
```

**When**: The component displays/edits current state passed from a parent — no need for event subscriptions because the parent re-passes updated props after each change.

## Registration and Exports

The `@customElement('epistola-xxx')` decorator **self-registers** the element. The browser knows about it once the module is imported.

Exports in `lib.ts` are **for TypeScript types only** — so that other modules can import the class for type annotations or `instanceof` checks. They do NOT affect registration:

```typescript
// lib.ts — exports are for types, not registration
export { EpistolaMyComponent } from './ui/EpistolaMyComponent.js'
```

## Engine Commands

**Reference**: `modules/editor/src/main/typescript/engine/commands.ts`

The `Command` union type defines all operations the engine supports:

```typescript
type Command =
    | InsertNode | RemoveNode | MoveNode
    | UpdateNodeProps | UpdateNodeStyles | SetStylePreset
    | UpdateDocumentStyles | UpdatePageSettings
    | AddColumnSlot | RemoveColumnSlot
```

Each command includes:
- An `applyCommand()` function that transforms the document
- Inverse command generation for undo support
- `CommandResult` = `{ ok: true, doc, inverse, structureChanged }` | `{ ok: false, error }`

To add a new command: add the type to the union, implement `applyCommand()`, and handle it in `EditorEngine.execute()`.

## Engine Events

**Reference**: `modules/editor/src/main/typescript/engine/events.ts`

```typescript
type EngineEvents = {
    'doc:change': { doc: TemplateDocument; indexes: DocumentIndexes }
    'selection:change': { nodeId: NodeId | null }
    'example:change': { index: number; example: object | undefined }
}
```

Subscribe: `engine.events.on('doc:change', handler)` — returns an unsubscribe function.

## Theme Editor Subdirectory

**Reference**: `modules/editor/src/main/typescript/theme-editor/`

The theme editor is a separate entry point with its own component tree:

- **Public API**: `mountThemeEditor(options)` in `theme-editor-lib.ts`
- **Root component**: `EpistolaThemeEditor.ts` with `init(themeData, onSave)` method
- **Sections**: `sections/` subdirectory with `BasicInfoSection`, `DocumentStylesSection`, `PageSettingsSection`, `PresetsSection`
- **State**: `ThemeEditorState.ts` manages form state, dirty tracking, autosave

The `init()` method pattern is unique to the theme editor — it initializes from server-provided data after the element is in the DOM.

## Conventions

- Tag name: `epistola-<kebab-name>` (e.g., `epistola-color-picker`)
- Class name: `Epistola<PascalName>` (e.g., `EpistolaColorPicker`)
- **Always use light DOM**: `override createRenderRoot() { return this }`
- Styling goes in a **separate CSS file**, not in the component
- Use `@property({ attribute: false })` for complex objects (engine, doc, etc.)
- Use `@state()` for internal reactive state
- Guard renders: `if (!this.engine) return nothing`
- Extract complex logic into testable service classes (like `PreviewService`, `SaveService`)

## File Locations

| What | Where |
|------|-------|
| UI components | `modules/editor/src/main/typescript/ui/` |
| Engine logic | `modules/editor/src/main/typescript/engine/` |
| Theme editor | `modules/editor/src/main/typescript/theme-editor/` |
| DnD | `modules/editor/src/main/typescript/dnd/` |
| ProseMirror | `modules/editor/src/main/typescript/prosemirror/` |
| Types | `modules/editor/src/main/typescript/types/` |
| Tests | Co-located as `*.test.ts` next to source |
| CSS | `modules/editor/src/main/resources/static/css/` |

## Checklist

- [ ] Component in `modules/editor/src/main/typescript/ui/`
- [ ] Export in `lib.ts` (for type access)
- [ ] CSS file if the component has styling
- [ ] Service class if logic is complex enough to unit test
- [ ] Tests (Vitest) for services/logic
- [ ] `pnpm --filter @epistola/editor test`
- [ ] `pnpm --filter @epistola/editor build`

## Gotchas

- Import Lit decorators from `lit/decorators.js` (with `.js` extension)
- Import types from sibling files with `.js` extension (TypeScript module resolution)
- `nothing` from `lit` is used to render nothing (not `null` or empty string)
- The engine's `execute()` method returns `CommandResult` — check `result.ok` before assuming success
- `deepFreeze` is applied to the document model — never mutate doc objects directly
